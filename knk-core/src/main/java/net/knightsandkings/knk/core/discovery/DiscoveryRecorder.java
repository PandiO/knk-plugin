package net.knightsandkings.knk.core.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
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
 *   <li><b>Spool</b> a grant that still fails transiently (network error, 5xx, 401/403 from an API key
 *   mismatch, 408/429) to {@link DiscoverySpool}; any other answer (another 4xx, an unreadable 200) is
 *   the server's final word and is only logged. Grants
 *   still in flight at shutdown are spooled by {@link #spoolInFlight()}.</li>
 *   <li><b>Replay</b> spooled discoveries ({@link #replay()} on enable and on a timer, {@link #replay(UUID)}
 *   on the player's next join) with source Replay, 50 ids per request. The server is idempotent, so a
 *   replay of something already granted just comes back as already discovered.</li>
 *   <li><b>Unknown user ids</b> (a player who joined while the API was down,
 *   {@link DiscoveryTracker#UNRESOLVED_USER}): a grant or a replayed file first looks the user id up by
 *   the player's UUID through the injected resolver. Unreachable: the grant is spooled under the UUID
 *   with the user id unknown, a replayed file is kept. No such user (404): dropped with a warning.
 *   The grant is never sent with a made-up id.</li>
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

    /**
     * @param userId the user id the grant was made for - resolved by UUID when the batch had none -
     *               or {@link DiscoveryTracker#UNRESOLVED_USER} when it is still unknown
     */
    public record Outcome(Status status, DiscoveryGrantResult result, int userId) {
        public Outcome(Status status, DiscoveryGrantResult result) {
            this(status, result, DiscoveryTracker.UNRESOLVED_USER);
        }
    }

    /** A spooled request delivered on replay; {@code userId} is always resolved. */
    public record Replayed(UUID playerId, int userId, List<PendingDiscovery> entries, DiscoveryGrantResult result) {}

    private record InFlight(UUID playerId, int userId, List<PendingDiscovery> entries) {}

    /** A user id lookup by UUID: found (userId > 0), no such user, or the API couldn't be asked. */
    private record Lookup(int userId, boolean unreachable) {
        static final Lookup NOT_FOUND = new Lookup(DiscoveryTracker.UNRESOLVED_USER, false);
        static final Lookup UNREACHABLE = new Lookup(DiscoveryTracker.UNRESOLVED_USER, true);

        boolean found() {
            return userId > 0;
        }
    }

    private final DiscoveriesApi api;
    private final RetryPolicy retryPolicy;
    private final DiscoverySpool spool;
    private final Logger logger;
    private final Function<UUID, CompletableFuture<Optional<Integer>>> userResolver;

    private final Map<Long, InFlight> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong nextToken = new AtomicLong();
    private final AtomicBoolean replaying = new AtomicBoolean();

    /** Without a user lookup: discoveries of players with an unknown user id stay spooled. */
    public DiscoveryRecorder(DiscoveriesApi api, RetryPolicy retryPolicy, DiscoverySpool spool, Logger logger) {
        this(api, retryPolicy, spool, logger,
                uuid -> CompletableFuture.failedFuture(new IllegalStateException("no user lookup configured")));
    }

    /**
     * @param userResolver looks a player's knk user id up by UUID: the id, empty when there is no such
     *                     user, or a failed future when the API can't be reached
     */
    public DiscoveryRecorder(DiscoveriesApi api, RetryPolicy retryPolicy, DiscoverySpool spool, Logger logger,
                             Function<UUID, CompletableFuture<Optional<Integer>>> userResolver) {
        this.api = Objects.requireNonNull(api, "api");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.spool = Objects.requireNonNull(spool, "spool");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.userResolver = Objects.requireNonNull(userResolver, "userResolver");
    }

    public DiscoverySpool spool() {
        return spool;
    }

    /**
     * Sends one grant request; spools it when the API can't be reached. A {@code userId} of
     * {@link DiscoveryTracker#UNRESOLVED_USER} is looked up by the player's UUID first; the outcome
     * carries the id it found.
     */
    public CompletableFuture<Outcome> grant(UUID playerId, int userId, List<PendingDiscovery> entries, DiscoverySource source) {
        List<PendingDiscovery> copy = List.copyOf(entries);
        long token = nextToken.incrementAndGet();
        inFlight.put(token, new InFlight(playerId, userId, copy));
        CompletableFuture<Outcome> outcome;
        if (userId > 0) {
            outcome = send(playerId, userId, copy, source);
        } else {
            outcome = lookUp(playerId).thenCompose(lookup -> {
                if (lookup.found()) {
                    return send(playerId, lookup.userId(), copy, source);
                }
                if (!lookup.unreachable()) {
                    logger.warning("[Discovery] Player " + playerId + " has no knk account; dropped the discovery of "
                            + regionIds(copy));
                    return CompletableFuture.completedFuture(new Outcome(Status.DROPPED, null));
                }
                return CompletableFuture.completedFuture(spoolFailed(playerId, DiscoveryTracker.UNRESOLVED_USER, copy,
                        "the user id lookup failed"));
            });
        }
        return outcome.whenComplete((ignored, error) -> inFlight.remove(token));
    }

    private CompletableFuture<Outcome> send(UUID playerId, int userId, List<PendingDiscovery> entries, DiscoverySource source) {
        List<String> regionIds = regionIds(entries);
        CompletableFuture<DiscoveryGrantResult> call;
        try {
            call = retryPolicy.executeAsync(() -> api.grant(userId, regionIds, source));
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        return call.handle((result, error) -> {
            if (error == null && result != null) {
                return new Outcome(Status.DELIVERED, result, userId);
            }
            if (error != null && isFinalRejection(error)) {
                logger.log(Level.WARNING, "[Discovery] The API refused the discovery of " + regionIds + " for user " + userId
                        + " (" + describe(error) + "); not retrying", unwrap(error));
                return new Outcome(Status.DROPPED, null, userId);
            }
            return spoolFailed(playerId, userId, entries, describe(error));
        });
    }

    private Outcome spoolFailed(UUID playerId, int userId, List<PendingDiscovery> entries, String why) {
        if (spool.add(playerId, userId, entries)) {
            logger.warning("[Discovery] Could not reach the API to discover " + regionIds(entries) + " for " + who(playerId, userId)
                    + " (" + why + "); spooled to " + spool.directory() + " for a later replay");
            return new Outcome(Status.SPOOLED, null, userId);
        }
        return new Outcome(Status.DROPPED, null, userId);
    }

    /**
     * Looks a player's knk user id up by UUID (a player tracked without one). Empty when there is no
     * such user or the API can't be reached. Never fails.
     */
    public CompletableFuture<OptionalInt> lookUpUserId(UUID playerId) {
        return lookUp(playerId).thenApply(lookup -> lookup.found() ? OptionalInt.of(lookup.userId()) : OptionalInt.empty());
    }

    private CompletableFuture<Lookup> lookUp(UUID playerId) {
        CompletableFuture<Optional<Integer>> call;
        try {
            call = userResolver.apply(playerId);
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        if (call == null) {
            call = CompletableFuture.failedFuture(new IllegalStateException("the user lookup returned nothing"));
        }
        return call.handle((found, error) -> {
            if (error != null) {
                ApiException apiError = apiException(error);
                if (apiError != null && apiError.getStatusCode() == 404) {
                    return Lookup.NOT_FOUND;
                }
                logger.fine("[Discovery] Could not look up the user id of " + playerId + " (" + describe(error) + ")");
                return Lookup.UNREACHABLE;
            }
            Integer userId = found == null ? null : found.orElse(null);
            return userId != null && userId > 0 ? new Lookup(userId, false) : Lookup.NOT_FOUND;
        });
    }

    /**
     * Spools discoveries that were never sent (the player quit or the server stopped first). The user id
     * may be {@link DiscoveryTracker#UNRESOLVED_USER}: the replay looks it up.
     */
    public boolean spoolPending(UUID playerId, int userId, List<PendingDiscovery> entries) {
        if (entries == null || entries.isEmpty()) {
            return true;
        }
        boolean saved = spool.add(playerId, userId, entries);
        if (saved) {
            logger.fine("[Discovery] Spooled " + entries.size() + " unsent discovery candidate(s) of " + who(playerId, userId));
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
                logger.warning("[Discovery] A discovery grant for " + who(call.playerId(), call.userId())
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
            chain = chain.thenCompose(ignored -> unreachable.get()
                    ? CompletableFuture.completedFuture(null)
                    : replayFile(file, delivered, unreachable));
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

    /** One player's file: looks the user id up first when it was spooled without one. */
    private CompletableFuture<Void> replayFile(DiscoverySpool.Pending file, List<Replayed> delivered, AtomicBoolean unreachable) {
        if (file.userResolved()) {
            return replayChunks(file, file.userId(), delivered, unreachable);
        }
        return lookUp(file.playerId()).thenCompose(lookup -> {
            if (lookup.found()) {
                return replayChunks(file, lookup.userId(), delivered, unreachable);
            }
            if (lookup.unreachable()) {
                unreachable.set(true);
                logger.fine("[Discovery] Spooled discoveries of " + file.playerId() + " wait: their user id can't be looked up yet");
            } else {
                spool.remove(file.playerId(), regionIds(file.entries()));
                logger.warning("[Discovery] Dropped " + file.entries().size() + " spooled discovery(ies) of player "
                        + file.playerId() + ": no knk account has that UUID");
            }
            return CompletableFuture.<Void>completedFuture(null);
        });
    }

    private CompletableFuture<Void> replayChunks(DiscoverySpool.Pending file, int userId, List<Replayed> delivered,
                                                 AtomicBoolean unreachable) {
        List<PendingDiscovery> entries = file.entries();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int from = 0; from < entries.size(); from += DiscoveriesApi.MAX_IDS_PER_REQUEST) {
            List<PendingDiscovery> chunk = List.copyOf(entries.subList(from, Math.min(entries.size(), from + DiscoveriesApi.MAX_IDS_PER_REQUEST)));
            chain = chain.thenCompose(ignored -> unreachable.get()
                    ? CompletableFuture.completedFuture(null)
                    : replayChunk(file.playerId(), userId, chunk, delivered, unreachable));
        }
        return chain;
    }

    private CompletableFuture<Void> replayChunk(UUID playerId, int userId, List<PendingDiscovery> chunk,
                                                List<Replayed> delivered, AtomicBoolean unreachable) {
        List<String> regionIds = regionIds(chunk);
        CompletableFuture<DiscoveryGrantResult> call;
        try {
            call = retryPolicy.executeAsync(() -> api.grant(userId, regionIds, DiscoverySource.REPLAY));
        } catch (RuntimeException e) {
            call = CompletableFuture.failedFuture(e);
        }
        return call.handle((result, error) -> {
            if (error == null && result != null) {
                spool.remove(playerId, regionIds);
                synchronized (delivered) {
                    delivered.add(new Replayed(playerId, userId, chunk, result));
                }
                logger.info("[Discovery] Delivered " + regionIds.size() + " spooled discovery(ies) of user " + userId
                        + ": " + result.granted().size() + " new");
            } else if (error != null && isFinalRejection(error)) {
                spool.remove(playerId, regionIds);
                logger.warning("[Discovery] Dropped " + regionIds.size() + " spooled discovery(ies) of user " + userId
                        + ": the API refused them (" + describe(error) + ")");
            } else {
                unreachable.set(true);
                logger.fine("[Discovery] Spooled discoveries still can't be delivered (" + describe(error) + ")");
            }
            return null;
        });
    }

    private static List<String> regionIds(List<PendingDiscovery> entries) {
        return entries.stream().map(PendingDiscovery::regionId).toList();
    }

    private static String who(UUID playerId, int userId) {
        return userId > 0 ? "user " + userId : "player " + playerId + " (user id not known yet)";
    }

    /**
     * The server's final answer: any HTTP status below 500 (a 4xx refusal, or a 200 whose body couldn't
     * be read - replaying it would get the same answer). Network failures and 5xx are transient, and so
     * are 401/403 (the API key doesn't match or isn't set on the API yet - a deployment slip that gets
     * fixed, after which the spooled discoveries must still be there) and 408/429.
     */
    public static boolean isFinalRejection(Throwable error) {
        ApiException api = apiException(error);
        if (api == null || api.getStatusCode() <= 0 || api.getStatusCode() >= 500) {
            return false;
        }
        return switch (api.getStatusCode()) {
            case 401, 403, 408, 429 -> false;
            default -> true;
        };
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
