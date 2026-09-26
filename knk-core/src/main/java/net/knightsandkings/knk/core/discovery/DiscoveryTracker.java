package net.knightsandkings.knk.core.discovery;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrant;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySkip;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySource;
import net.knightsandkings.knk.core.domain.discovery.KnownDiscovery;
import net.knightsandkings.knk.core.ports.api.DiscoveriesApi;

/**
 * Per-player discovery state (docs/specs/domain-discovery DESIGN.md §3.6), so a player walking
 * around costs an API call only for regions that may still be undiscovered domains:
 * <ul>
 *   <li><b>known</b> - region ids (and domain ids) the player has discovered, loaded once per
 *   session from {@code GET …/discoveries/known} and extended from every grant response;</li>
 *   <li><b>notDomain</b> - region ids the server said aren't domains, remembered for a while;</li>
 *   <li><b>pending</b> - candidates waiting for the next flush, in the order they were seen;</li>
 *   <li><b>inFlight</b> - the one request a player may have running;</li>
 *   <li><b>suppressed</b> - ids spooled (API down) or refused this session: the spool replays them,
 *   so they aren't sent again.</li>
 * </ul>
 * Candidates seen before the known set has loaded wait in pending and are filtered once it arrives.
 * If loading fails, they are sent anyway (the server dedups) - still at most
 * {@code maxRequestsPerMinute} requests a minute per player.
 *
 * <p>No Bukkit types; thread-safe (every method is synchronized). The caller passes the time.
 */
public final class DiscoveryTracker {

    /**
     * One grant request's worth of a player's pending candidates, all with the same source.
     * {@code session} tells an answer for an earlier session (the player rejoined meanwhile) apart.
     */
    public record Batch(UUID playerId, int userId, long session, DiscoverySource source, List<PendingDiscovery> entries) {
        public Batch {
            entries = List.copyOf(entries);
        }

        public List<String> regionIds() {
            return entries.stream().map(PendingDiscovery::regionId).toList();
        }
    }

    public static final Duration DEFAULT_NOT_DOMAIN_TTL = Duration.ofMinutes(10);
    public static final Duration DEFAULT_RATE_LIMITED_DELAY = Duration.ofSeconds(60);
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private static final class Session {
        final int userId;
        final long id;
        boolean knownLoaded;
        final Set<String> knownRegions = new HashSet<>();
        final Set<Integer> knownDomains = new HashSet<>();
        final Map<String, Instant> notDomainUntil = new HashMap<>();
        final LinkedHashMap<String, PendingDiscovery> pending = new LinkedHashMap<>();
        final Map<String, Instant> notBefore = new HashMap<>();
        final Set<String> inFlight = new HashSet<>();
        final Set<String> suppressed = new HashSet<>();
        final Deque<Instant> requests = new ArrayDeque<>();

        Session(int userId, long id) {
            this.userId = userId;
            this.id = id;
        }
    }

    private final int maxIdsPerRequest;
    private final int maxRequestsPerMinute;
    private final Duration notDomainTtl;
    private final Duration rateLimitedDelay;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private long nextSessionId;

    public DiscoveryTracker(int maxRequestsPerMinute) {
        this(DiscoveriesApi.MAX_IDS_PER_REQUEST, maxRequestsPerMinute, DEFAULT_NOT_DOMAIN_TTL, DEFAULT_RATE_LIMITED_DELAY);
    }

    public DiscoveryTracker(int maxIdsPerRequest, int maxRequestsPerMinute, Duration notDomainTtl, Duration rateLimitedDelay) {
        this.maxIdsPerRequest = Math.max(1, Math.min(maxIdsPerRequest, DiscoveriesApi.MAX_IDS_PER_REQUEST));
        this.maxRequestsPerMinute = Math.max(1, maxRequestsPerMinute);
        this.notDomainTtl = notDomainTtl;
        this.rateLimitedDelay = rateLimitedDelay;
    }

    // ==================== Sessions ====================

    /** Starts tracking a player (their account has loaded). Keeps an existing session for the same user. */
    public synchronized void startSession(UUID playerId, int userId) {
        Session existing = sessions.get(playerId);
        if (existing == null || existing.userId != userId) {
            sessions.put(playerId, new Session(userId, ++nextSessionId));
        }
    }

    public synchronized boolean hasSession(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    public synchronized OptionalInt userId(UUID playerId) {
        Session session = sessions.get(playerId);
        return session == null ? OptionalInt.empty() : OptionalInt.of(session.userId);
    }

    /** The player's discovered domains arrived: remember them and drop pending candidates they cover. */
    public synchronized void knownLoaded(UUID playerId, Collection<KnownDiscovery> known) {
        Session session = sessions.get(playerId);
        if (session == null) {
            return;
        }
        if (known != null) {
            for (KnownDiscovery discovery : known) {
                session.knownDomains.add(discovery.domainId());
                if (discovery.wgRegionId() != null && !discovery.wgRegionId().isBlank()) {
                    session.knownRegions.add(PendingDiscovery.key(discovery.wgRegionId()));
                }
            }
        }
        session.knownLoaded = true;
        session.pending.keySet().removeIf(session.knownRegions::contains);
    }

    /** Loading the known set failed: send candidates anyway (the server dedups), still rate limited. */
    public synchronized void knownLoadFailed(UUID playerId) {
        Session session = sessions.get(playerId);
        if (session != null) {
            session.knownLoaded = true;
        }
    }

    /**
     * Stops tracking a player (quit) and returns their pending candidates, which the caller spools.
     * Requests in flight are not returned - the recorder spools those if they fail.
     */
    public synchronized List<PendingDiscovery> endSession(UUID playerId) {
        Session session = sessions.remove(playerId);
        return session == null ? List.of() : List.copyOf(session.pending.values());
    }

    /** Ends every session (shutdown); pending candidates per player with their user id. */
    public synchronized Map<UUID, Map.Entry<Integer, List<PendingDiscovery>>> endAll() {
        Map<UUID, Map.Entry<Integer, List<PendingDiscovery>>> drained = new LinkedHashMap<>();
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            if (!entry.getValue().pending.isEmpty()) {
                drained.put(entry.getKey(), Map.entry(entry.getValue().userId, List.copyOf(entry.getValue().pending.values())));
            }
        }
        sessions.clear();
        return drained;
    }

    // ==================== Candidates ====================

    /**
     * Whether a region the player is in may be an undiscovered domain worth confirming: they have a
     * session and the id isn't known, a recent non-domain, already pending, in flight, or suppressed.
     */
    public synchronized boolean isCandidate(UUID playerId, String regionId, Instant now) {
        Session session = sessions.get(playerId);
        return session != null && isCandidate(session, PendingDiscovery.key(regionId), now);
    }

    private static boolean isCandidate(Session session, String key, Instant now) {
        if (key.isEmpty() || session.knownRegions.contains(key) || session.pending.containsKey(key)
                || session.inFlight.contains(key) || session.suppressed.contains(key)) {
            return false;
        }
        Instant until = session.notDomainUntil.get(key);
        if (until != null) {
            if (now.isBefore(until)) {
                return false;
            }
            session.notDomainUntil.remove(key);
        }
        return true;
    }

    /** Queues a confirmed candidate. Returns false when it isn't one (see {@link #isCandidate}). */
    public synchronized boolean offer(UUID playerId, String regionId, DiscoverySource source, Instant now) {
        Session session = sessions.get(playerId);
        if (session == null || regionId == null) {
            return false;
        }
        String key = PendingDiscovery.key(regionId);
        if (!isCandidate(session, key, now)) {
            return false;
        }
        session.pending.put(key, new PendingDiscovery(regionId.trim(), source, now));
        return true;
    }

    // ==================== Flushing ====================

    public synchronized List<UUID> playersWithPending() {
        List<UUID> players = new ArrayList<>();
        sessions.forEach((id, session) -> {
            if (!session.pending.isEmpty()) {
                players.add(id);
            }
        });
        return players;
    }

    /**
     * Takes the player's next request: nothing while the known set is loading, a request is in flight,
     * or the per-minute limit is used up; otherwise up to 50 due candidates of the oldest one's source.
     */
    public synchronized Optional<Batch> nextBatch(UUID playerId, Instant now) {
        Session session = sessions.get(playerId);
        if (session == null || !session.knownLoaded || !session.inFlight.isEmpty() || session.pending.isEmpty()) {
            return Optional.empty();
        }
        while (!session.requests.isEmpty() && !session.requests.peekFirst().isAfter(now.minus(RATE_WINDOW))) {
            session.requests.pollFirst();
        }
        if (session.requests.size() >= maxRequestsPerMinute) {
            return Optional.empty();
        }

        DiscoverySource source = null;
        List<PendingDiscovery> entries = new ArrayList<>();
        Iterator<Map.Entry<String, PendingDiscovery>> it = session.pending.entrySet().iterator();
        while (it.hasNext() && entries.size() < maxIdsPerRequest) {
            Map.Entry<String, PendingDiscovery> entry = it.next();
            Instant due = session.notBefore.get(entry.getKey());
            if (due != null && now.isBefore(due)) {
                continue;
            }
            if (source == null) {
                source = entry.getValue().source();
            } else if (entry.getValue().source() != source) {
                continue;
            }
            entries.add(entry.getValue());
            session.inFlight.add(entry.getKey());
            session.notBefore.remove(entry.getKey());
            it.remove();
        }
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        session.requests.addLast(now);
        return Optional.of(new Batch(playerId, session.userId, session.id, source, entries));
    }

    /**
     * Applies the server's answer to a batch: granted, already discovered and disabled ids become known,
     * NotADomain ids are remembered for a while, RateLimited ids go back to pending after a delay.
     * Region ids of granted ancestors become known too.
     */
    public synchronized void completed(Batch batch, DiscoveryGrantResult result, Instant now) {
        Session session = sessions.get(batch.playerId());
        if (session == null || session.id != batch.session()) {
            return;
        }
        batch.entries().forEach(e -> session.inFlight.remove(e.key()));
        apply(session, batch.entries(), result, now);
    }

    /**
     * The batch wasn't delivered: it was spooled (API unreachable) or refused (4xx). Either way these ids
     * aren't sent again this session - the spool replays the spooled ones.
     */
    public synchronized void deferred(Batch batch) {
        Session session = sessions.get(batch.playerId());
        if (session == null || session.id != batch.session()) {
            return;
        }
        batch.entries().forEach(e -> {
            session.inFlight.remove(e.key());
            session.suppressed.add(e.key());
        });
    }

    /** A spooled request for this player was delivered on replay: apply it like a live answer. */
    public synchronized void replayed(UUID playerId, int userId, List<PendingDiscovery> entries, DiscoveryGrantResult result, Instant now) {
        Session session = sessions.get(playerId);
        if (session == null || session.userId != userId) {
            return;
        }
        entries.forEach(e -> session.suppressed.remove(e.key()));
        apply(session, entries, result, now);
    }

    private void apply(Session session, List<PendingDiscovery> sent, DiscoveryGrantResult result, Instant now) {
        Set<String> rateLimited = new HashSet<>();
        Set<String> notDomain = new HashSet<>();
        for (DiscoverySkip skip : result.skipped()) {
            if (DiscoverySkip.RATE_LIMITED.equals(skip.reason())) {
                rateLimited.add(PendingDiscovery.key(skip.key()));
            } else if (DiscoverySkip.NOT_A_DOMAIN.equals(skip.reason())) {
                notDomain.add(PendingDiscovery.key(skip.key()));
            }
        }
        for (PendingDiscovery entry : sent) {
            String key = entry.key();
            if (rateLimited.contains(key)) {
                session.pending.putIfAbsent(key, entry);
                session.notBefore.put(key, now.plus(rateLimitedDelay));
            } else if (notDomain.contains(key)) {
                session.notDomainUntil.put(key, now.plus(notDomainTtl));
            } else {
                // Granted, already discovered or disabled: nothing more to ask for this session.
                session.knownRegions.add(key);
            }
        }
        for (DiscoveryGrant grant : result.granted()) {
            session.knownDomains.add(grant.domainId());
            if (grant.wgRegionId() != null && !grant.wgRegionId().isBlank()) {
                String key = PendingDiscovery.key(grant.wgRegionId());
                session.knownRegions.add(key);
                session.pending.remove(key);
                session.notBefore.remove(key);
            }
        }
        session.knownDomains.addAll(result.alreadyDiscovered());
    }

    // ==================== Status ====================

    public synchronized int sessionCount() {
        return sessions.size();
    }

    public synchronized int pendingCount() {
        return sessions.values().stream().mapToInt(s -> s.pending.size()).sum();
    }

    public synchronized int knownCount(UUID playerId) {
        Session session = sessions.get(playerId);
        return session == null ? 0 : session.knownRegions.size();
    }
}
