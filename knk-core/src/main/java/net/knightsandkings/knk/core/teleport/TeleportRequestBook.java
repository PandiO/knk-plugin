package net.knightsandkings.knk.core.teleport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pending player teleport requests - {@code /tpa} and {@code /tpahere}
 * (docs/specs/teleport/DESIGN.md §3.5, Phase 3). Pure state machine, time passed in:
 * <ul>
 *   <li>A request can be answered until {@code expireSeconds} after it was sent (v1: 30 s); after
 *       that it counts as gone and {@link #sweepExpired} hands it out once for the "expired" notice.</li>
 *   <li>One outgoing request per requester: a new one replaces the old one, unless it's the same
 *       request again (same target, same direction) - that is {@link SendStatus#DUPLICATE}.</li>
 *   <li>One pending request per pair of players: if the target already asked the requester,
 *       that's {@link SendStatus#REVERSE_PENDING} (answer theirs instead).</li>
 *   <li>At most {@code maxIncoming} pending requests per target; the oldest is dropped.</li>
 *   <li>Answering (accept or deny) or withdrawing removes the request, so a second
 *       {@code /tpaccept} finds nothing and a request can't be both accepted and denied.</li>
 * </ul>
 * In memory only; a restart drops pending requests (fine for a 30 s window, DESIGN §4 D12).
 * Meant for the main thread; the methods are synchronized anyway so a stray call from another
 * thread can't corrupt it.
 */
public final class TeleportRequestBook {

    /** Who moves when the request is accepted. */
    public enum Direction {
        /** {@code /tpa}: the requester goes to the target. */
        TO_TARGET,
        /** {@code /tpahere}: the target comes to the requester. */
        TO_REQUESTER
    }

    public record Request(long id, UUID requester, UUID target, Direction direction,
                          long createdAtMillis, long expiresAtMillis) {
        public Request {
            Objects.requireNonNull(requester, "requester must not be null");
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
        }

        /** The player who is teleported on accept. */
        public UUID mover() {
            return direction == Direction.TO_TARGET ? requester : target;
        }

        /** The player whose (live) location is the destination. */
        public UUID stationary() {
            return direction == Direction.TO_TARGET ? target : requester;
        }

        public boolean involves(UUID player) {
            return requester.equals(player) || target.equals(player);
        }

        public boolean isExpired(long nowMillis) {
            return nowMillis >= expiresAtMillis;
        }

        /** Whole seconds left to answer, rounded up; 0 once expired. */
        public int secondsLeft(long nowMillis) {
            long left = expiresAtMillis - nowMillis;
            return left <= 0 ? 0 : (int) ((left + 999) / 1000);
        }
    }

    public enum SendStatus {
        /** Stored; the target should be told. */
        SENT,
        /** The same request is already pending ({@link SendResult#request()} is that one). */
        DUPLICATE,
        /** The target already asked the requester ({@link SendResult#request()} is theirs). */
        REVERSE_PENDING,
        /** A player can't ask themselves. */
        SELF
    }

    /**
     * @param request  the new request (SENT), the pending one (DUPLICATE, REVERSE_PENDING), or null (SELF)
     * @param replaced the requester's previous outgoing request it replaced, if any (SENT only)
     * @param dropped  the target's oldest requests dropped to stay within the per-target cap (SENT only)
     */
    public record SendResult(SendStatus status, Request request, Optional<Request> replaced, List<Request> dropped) {
        public SendResult {
            Objects.requireNonNull(status, "status must not be null");
            replaced = replaced != null ? replaced : Optional.empty();
            dropped = dropped != null ? List.copyOf(dropped) : List.of();
        }

        static SendResult refused(SendStatus status, Request pending) {
            return new SendResult(status, pending, Optional.empty(), List.of());
        }

        public boolean isSent() {
            return status == SendStatus.SENT;
        }
    }

    /** Newest first; the id breaks ties between requests sent in the same millisecond. */
    private static final Comparator<Request> NEWEST_FIRST =
        Comparator.comparingLong(Request::createdAtMillis).thenComparingLong(Request::id).reversed();

    /** Keyed by requester - the one-outgoing-per-requester rule is the map's key. */
    private final Map<UUID, Request> byRequester = new LinkedHashMap<>();
    private long nextId = 1;
    private int expireSeconds;
    private int maxIncoming;

    public TeleportRequestBook(int expireSeconds, int maxIncoming) {
        configure(expireSeconds, maxIncoming);
    }

    public TeleportRequestBook(TeleportRequestSettings settings) {
        this(settings.expireSeconds(), settings.maxIncoming());
    }

    /** New limits (config reload); applies to requests sent from now on. */
    public synchronized void configure(int expireSeconds, int maxIncoming) {
        this.expireSeconds = Math.max(1, expireSeconds);
        this.maxIncoming = Math.max(1, maxIncoming);
    }

    public synchronized int expireSeconds() {
        return expireSeconds;
    }

    /** Record a request from {@code requester} to {@code target}. */
    public synchronized SendResult send(UUID requester, UUID target, Direction direction, long nowMillis) {
        Objects.requireNonNull(requester, "requester must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        if (requester.equals(target)) {
            return SendResult.refused(SendStatus.SELF, null);
        }
        Request existing = live(byRequester.get(requester), nowMillis);
        if (existing != null && existing.target().equals(target) && existing.direction() == direction) {
            return SendResult.refused(SendStatus.DUPLICATE, existing);
        }
        Request reverse = live(byRequester.get(target), nowMillis);
        if (reverse != null && reverse.target().equals(requester)) {
            return SendResult.refused(SendStatus.REVERSE_PENDING, reverse);
        }

        byRequester.remove(requester);
        List<Request> incoming = incomingLive(target, nowMillis);
        List<Request> dropped = new ArrayList<>();
        // Oldest last in NEWEST_FIRST order; drop from the end until there's room for one more.
        while (incoming.size() >= maxIncoming) {
            Request oldest = incoming.remove(incoming.size() - 1);
            byRequester.remove(oldest.requester(), oldest);
            dropped.add(oldest);
        }
        Request request = new Request(nextId++, requester, target, direction, nowMillis,
            nowMillis + expireSeconds * 1000L);
        byRequester.put(requester, request);
        return new SendResult(SendStatus.SENT, request, Optional.ofNullable(existing), dropped);
    }

    /**
     * Take (remove) the request {@code target} is answering: the newest pending one, or the one
     * from {@code requester} when given. Empty when there's none - including when it expired or
     * was already answered.
     */
    public synchronized Optional<Request> take(UUID target, UUID requester, long nowMillis) {
        for (Request request : incomingLive(target, nowMillis)) {
            if (requester == null || request.requester().equals(requester)) {
                byRequester.remove(request.requester(), request);
                return Optional.of(request);
            }
        }
        return Optional.empty();
    }

    /** Withdraw {@code requester}'s outgoing request, if one is pending. */
    public synchronized Optional<Request> cancelOutgoing(UUID requester, long nowMillis) {
        Request request = live(byRequester.get(requester), nowMillis);
        if (request == null) {
            return Optional.empty();
        }
        byRequester.remove(requester, request);
        return Optional.of(request);
    }

    public synchronized Optional<Request> outgoing(UUID requester, long nowMillis) {
        return Optional.ofNullable(live(byRequester.get(requester), nowMillis));
    }

    /** Pending requests to {@code target}, newest first. */
    public synchronized List<Request> incoming(UUID target, long nowMillis) {
        return List.copyOf(incomingLive(target, nowMillis));
    }

    /** Remove and return every expired request (each is returned once, for the "expired" notice). */
    public synchronized List<Request> sweepExpired(long nowMillis) {
        List<Request> expired = new ArrayList<>();
        byRequester.values().removeIf(request -> {
            if (request.isExpired(nowMillis)) {
                expired.add(request);
                return true;
            }
            return false;
        });
        return expired;
    }

    /** Remove every request {@code player} sent or received (quit, death); returns what was pending. */
    public synchronized List<Request> clear(UUID player, long nowMillis) {
        List<Request> removed = new ArrayList<>();
        byRequester.values().removeIf(request -> {
            if (request.involves(player)) {
                if (!request.isExpired(nowMillis)) {
                    removed.add(request);
                }
                return true;
            }
            return false;
        });
        return removed;
    }

    /** Stored requests, expired-but-not-swept ones included. */
    public synchronized int size() {
        return byRequester.size();
    }

    private static Request live(Request request, long nowMillis) {
        return request != null && !request.isExpired(nowMillis) ? request : null;
    }

    private List<Request> incomingLive(UUID target, long nowMillis) {
        List<Request> incoming = new ArrayList<>();
        for (Request request : byRequester.values()) {
            if (request.target().equals(target) && !request.isExpired(nowMillis)) {
                incoming.add(request);
            }
        }
        incoming.sort(NEWEST_FIRST);
        return incoming;
    }
}
