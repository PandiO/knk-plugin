package net.knightsandkings.knk.core.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.dataaccess.RetryPolicy;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySource;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.DiscoveriesApi;

/**
 * The grant call with retry and spooling (docs/specs/domain-discovery DESIGN.md §3.6, modelled on
 * the siege match recorder):
 * <ul>
 *   <li><b>Retry</b> each grant with the existing {@link RetryPolicy} (it retries network failures only).</li>
 *   <li><b>Spool</b> a grant that still fails transiently (network error, 5xx) to {@link DiscoverySpool};
 *   any other answer (4xx, an unreadable 200) is the server's final word and is only logged. Grants
 *   still in flight at shutdown are spooled by {@link #spoolInFlight()}.</li>
 *   <li><b>Replay</b> spooled discoveries ({@link #replay()} on enable and on a timer, {@link #replay(UUID)}
 *   on the player's next join) with source Replay, 50 ids per request. The server is idempotent, so a
 *   replay of something already granted just comes back as already discovered.</li>
 * </ul>
 * Every returned future completes normally. No Bukkit types; callers hop to the main thread themselves.
 */
public final class DiscoveryRecorder {

    public enum Status {
        /** The server answered; see the result. */
        DELIVERED,
        /** Unreachable: written to the spool for a later replay. */
        SPOOLED,
        /** Refused (4xx) or couldn't be spooled: given up. */
        DROPPED
    }

    public record Outcome(Status status, DiscoveryGrantResult result) {}

    /** A spooled request delivered on replay. */
    public record Replayed(UUID playerId, int userId, List<PendingDiscovery> entries, DiscoveryGrantResult result) {}

    private record InFlight(UUID playerId, int userId, List<PendingDiscovery> entries) {}

    private final DiscoveriesApi api;
    private final RetryPolicy retryPolicy;
    private final DiscoverySpool spool;
    private final Logger logger;

    private final Map<Long, InFlight> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong nextToken = new AtomicLong();
    private final AtomicBoolean replaying = new AtomicBoolean();

    public DiscoveryRecorder(DiscoveriesApi api, RetryPolicy retryPolicy, DiscoverySpool spool, Logger logger) {
        this.api = Objects.requireNonNull(api, "api");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.spool = Objects.requireNonNull(spool, "spool");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public DiscoverySpool spool() {
        return spool;
    }

    /** Sends one grant request; spools it when the API can't be reached. */
    public CompletableFuture<Outcome> grant(UUID playerId, int userId, List<PendingDiscovery> entries, DiscoverySource source) {
        List<PendingDiscovery> copy = List.copyOf(entries);
        List<String> regionIds = copy.stream().map(PendingDiscovery::regionId).toList();
        long token = nextToken.incrementAndGet();
        inFlight.put(token, new InFlight(playerId, userId, copy));
        CompletableFuture<DiscoveryGrantResult> call;
        try {
            call = retryPolicy.executeAsync(() -> api.grant(userId, regionIds, source));
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        return call.handle((result, error) -> {
            inFlight.remove(token);
            if (error == null && result != null) {
                return new Outcome(Status.DELIVERED, result);
            }
            if (error != null && isFinalRejection(error)) {
                logger.log(Level.WARNING, "[Discovery] The API refused the discovery of " + regionIds + " for user " + userId
                        + " (" + describe(error) + "); not retrying", unwrap(error));
                return new Outcome(Status.DROPPED, null);
            }
            if (spool.add(playerId, userId, copy)) {
                logger.warning("[Discovery] Could not reach the API to discover " + regionIds + " for user " + userId
                        + " (" + describe(error) + "); spooled to " + spool.directory() + " for a later replay");
                return new Outcome(Status.SPOOLED, null);
            }
            return new Outcome(Status.DROPPED, null);
        });
    }

    /** Spools discoveries that were never sent (the player quit or the server stopped first). */
    public boolean spoolPending(UUID playerId, int userId, List<PendingDiscovery> entries) {
        if (entries == null || entries.isEmpty()) {
            return true;
        }
        boolean saved = spool.add(playerId, userId, entries);
        if (saved) {
            logger.fine("[Discovery] Spooled " + entries.size() + " unsent discovery candidate(s) of user " + userId);
        }
        return saved;
    }

    /**
     * Writes every grant still in flight to the spool (call on disable). If the call succeeds after all,
     * the replay is answered as already discovered - harmless.
     */
    public void spoolInFlight() {
        for (InFlight call : new ArrayList<>(inFlight.values())) {
            if (spool.add(call.playerId(), call.userId(), call.entries())) {
                logger.warning("[Discovery] A discovery grant for user " + call.userId()
                        + " was still being sent at shutdown; spooled for the next start");
            }
        }
    }

    /** Replays every spooled file (no-op while another replay runs). Stops at the first transient failure. */
    public CompletableFuture<List<Replayed>> replay() {
        return replayFiles(null);
    }

    /** Replays one player's spooled discoveries (their next join). */
    public CompletableFuture<List<Replayed>> replay(UUID playerId) {
        return replayFiles(Objects.requireNonNull(playerId, "playerId"));
    }

    private CompletableFuture<List<Replayed>> replayFiles(UUID only) {
        if (!replaying.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<DiscoverySpool.Pending> files;
        try {
            files = only == null ? spool.list() : spool.get(only).stream().toList();
        } catch (RuntimeException e) {
            replaying.set(false);
            logger.log(Level.WARNING, "[Discovery] Could not read the discovery spool", e);
            return CompletableFuture.completedFuture(List.of());
        }
        List<Replayed> delivered = new ArrayList<>();
        AtomicBoolean unreachable = new AtomicBoolean();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (DiscoverySpool.Pending file : files) {
            List<PendingDiscovery> entries = file.entries();
            for (int from = 0; from < entries.size(); from += DiscoveriesApi.MAX_IDS_PER_REQUEST) {
                List<PendingDiscovery> chunk = entries.subList(from, Math.min(entries.size(), from + DiscoveriesApi.MAX_IDS_PER_REQUEST));
                chain = chain.thenCompose(ignored -> unreachable.get()
                        ? CompletableFuture.completedFuture(null)
                        : replayChunk(file, List.copyOf(chunk), delivered, unreachable));
            }
        }
        return chain.handle((ignored, error) -> {
            replaying.set(false);
            if (error != null) {
                logger.log(Level.WARNING, "[Discovery] Replaying spooled discoveries failed", unwrap(error));
            }
            synchronized (delivered) {
                return List.copyOf(delivered);
            }
        });
    }

    private CompletableFuture<Void> replayChunk(DiscoverySpool.Pending file, List<PendingDiscovery> chunk,
                                                List<Replayed> delivered, AtomicBoolean unreachable) {
        List<String> regionIds = chunk.stream().map(PendingDiscovery::regionId).toList();
        CompletableFuture<DiscoveryGrantResult> call;
        try {
            call = retryPolicy.executeAsync(() -> api.grant(file.userId(), regionIds, DiscoverySource.REPLAY));
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        return call.handle((result, error) -> {
            if (error == null && result != null) {
                spool.remove(file.playerId(), regionIds);
                synchronized (delivered) {
                    delivered.add(new Replayed(file.playerId(), file.userId(), chunk, result));
                }
                logger.info("[Discovery] Delivered " + regionIds.size() + " spooled discovery(ies) of user " + file.userId()
                        + ": " + result.granted().size() + " new");
            } else if (error != null && isFinalRejection(error)) {
                spool.remove(file.playerId(), regionIds);
                logger.warning("[Discovery] Dropped " + regionIds.size() + " spooled discovery(ies) of user " + file.userId()
                        + ": the API refused them (" + describe(error) + ")");
            } else {
                unreachable.set(true);
                logger.fine("[Discovery] Spooled discoveries still can't be delivered (" + describe(error) + ")");
            }
            return null;
        });
    }

    /**
     * The server's final answer: any HTTP status below 500 (a 4xx refusal, or a 200 whose body couldn't
     * be read - replaying it would get the same answer). Network failures and 5xx are transient.
     */
    public static boolean isFinalRejection(Throwable error) {
        ApiException api = apiException(error);
        return api != null && api.getStatusCode() > 0 && api.getStatusCode() < 500;
    }

    private static ApiException apiException(Throwable error) {
        Throwable t = error;
        while (t != null) {
            if (t instanceof ApiException api) {
                return api;
            }
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }
        return null;
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "no result";
        }
        ApiException api = apiException(error);
        if (api != null && api.getStatusCode() > 0) {
            return "HTTP " + api.getStatusCode()
                    + (api.getResponseBody() == null || api.getResponseBody().isBlank() ? "" : ": " + api.getResponseBody());
        }
        Throwable root = unwrap(error);
        return root == null ? "unknown error" : root.getClass().getSimpleName() + ": " + root.getMessage();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
