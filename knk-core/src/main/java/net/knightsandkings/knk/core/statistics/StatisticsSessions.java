package net.knightsandkings.knk.core.statistics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;

/**
 * The online players' statistics sessions (DESIGN.md §F.2-§F.3, §F.9): one session per join with a
 * known user id, ending at quit/kick/server stop; a reconnect is a new session (and a login, which
 * the API derives from the start entry). Each session owns an {@link AfkTracker} and the movement
 * totals since the last flush, so a move event only adds to a few primitive fields.
 * <p>
 * <b>Unknown user ids</b> (the player joined while the API was down): the session is tracked under
 * the player's UUID; its classified time, distance, falls and counters are held in the session until
 * {@link #resolve} gives it a user id - then the start (at the original join instant) and everything
 * held go to the buffer. A session that ends before that is kept (at most {@link #MAX_ORPHANS}, the
 * oldest dropped) and resolved the same way.
 * <p>
 * Not thread-safe: the plugin calls it on the server main thread only. Writes go to the thread-safe
 * {@link StatisticsBuffer}.
 */
public final class StatisticsSessions {

    public static final int MAX_ORPHANS = 1_024;

    /** A counter or record held while the user id is unknown. */
    private record HeldValue(StatisticsMetric metric, StatisticsContext context, double value, Instant at) {
    }

    private static final class Session {
        final UUID playerId;
        int userId;
        final UUID sessionKey;
        final Instant startedAt;
        final AfkTracker afk;
        final List<AfkTracker.Slice> heldSlices = new ArrayList<>();
        final List<HeldValue> heldValues = new ArrayList<>();
        StatisticsBatch.EndReason endReason;
        Instant endedAt;
        // movement since the last flush (DESIGN §F.9), blocks
        double foot;
        double swim;
        double flying;
        double vehicle;

        Session(UUID playerId, int userId, UUID sessionKey, Instant startedAt, Duration idle) {
            this.playerId = playerId;
            this.userId = userId;
            this.sessionKey = sessionKey;
            this.startedAt = startedAt;
            this.afk = new AfkTracker(startedAt, idle);
        }

        boolean resolved() {
            return userId > 0;
        }
    }

    private final StatisticsBuffer buffer;
    private final Duration idle;
    private final Supplier<UUID> sessionKeys;
    private final Map<UUID, Session> active = new HashMap<>();
    private final LinkedHashMap<UUID, Session> orphans = new LinkedHashMap<>();
    private int droppedOrphans;

    /**
     * @param idle        automatic AFK threshold ({@code statistics.afk.idle-seconds}); zero = only {@code /afk}
     * @param sessionKeys a new random key per session (injected for tests)
     */
    public StatisticsSessions(StatisticsBuffer buffer, Duration idle, Supplier<UUID> sessionKeys) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        this.idle = idle == null ? Duration.ZERO : idle;
        this.sessionKeys = Objects.requireNonNull(sessionKeys, "sessionKeys");
    }

    public StatisticsBuffer buffer() {
        return buffer;
    }

    // ===== lifecycle =====

    /**
     * A join (user data loaded). {@code userId} &le; 0 = not known yet. A session still open for the
     * player (a second load event) is ended first as a quit.
     */
    public void start(UUID playerId, int userId, Instant now) {
        Objects.requireNonNull(playerId, "playerId");
        if (active.containsKey(playerId)) {
            end(playerId, StatisticsBatch.EndReason.Quit, now);
        }
        Session session = new Session(playerId, Math.max(0, userId), sessionKeys.get(), now, idle);
        active.put(playerId, session);
        if (session.resolved()) {
            buffer.addSession(StatisticsBatch.SessionEntry.start(session.sessionKey, session.userId, session.startedAt));
        }
    }

    /** The player left: everything accrued is handed to the buffer (or held, see the class comment). */
    public void end(UUID playerId, StatisticsBatch.EndReason reason, Instant now) {
        Session session = active.remove(playerId);
        if (session == null) {
            return;
        }
        session.endReason = reason == null ? StatisticsBatch.EndReason.Quit : reason;
        session.endedAt = now;
        hand(session, session.afk.quit(now), now);
        if (session.resolved()) {
            buffer.addSession(StatisticsBatch.SessionEntry.end(session.sessionKey, session.userId, now, session.endReason));
        } else {
            orphans.put(session.sessionKey, session);
            while (orphans.size() > MAX_ORPHANS) {
                UUID eldest = orphans.keySet().iterator().next();
                orphans.remove(eldest);
                droppedOrphans++;
            }
        }
    }

    /** Server stop: ends every session (reason {@code ServerStop}). */
    public void endAll(StatisticsBatch.EndReason reason, Instant now) {
        for (UUID playerId : new ArrayList<>(active.keySet())) {
            end(playerId, reason, now);
        }
    }

    /**
     * Flush step: hands every session's classified time and movement since the last flush to the
     * buffer (held while the user id is unknown).
     */
    public void accrueAll(Instant now) {
        for (Session session : active.values()) {
            hand(session, session.afk.accrue(now), now);
        }
    }

    /**
     * Gives the player's open or ended session(s) without a user id their id: the start (at the join
     * instant), the held time and values and - for an ended one - the end go to the buffer. Returns
     * true when something was resolved.
     */
    public boolean resolve(UUID playerId, int userId) {
        if (userId <= 0) {
            return false;
        }
        boolean any = false;
        Session open = active.get(playerId);
        if (open != null && !open.resolved()) {
            open.userId = userId;
            release(open);
            any = true;
        }
        for (var iterator = orphans.values().iterator(); iterator.hasNext(); ) {
            Session orphan = iterator.next();
            if (!orphan.playerId.equals(playerId)) {
                continue;
            }
            orphan.userId = userId;
            release(orphan);
            buffer.addSession(StatisticsBatch.SessionEntry.end(orphan.sessionKey, userId, orphan.endedAt, orphan.endReason));
            iterator.remove();
            any = true;
        }
        return any;
    }

    /** Players (online or not) whose sessions still wait for a user id. */
    public Set<UUID> unresolvedPlayers() {
        Set<UUID> players = new LinkedHashSet<>();
        active.values().stream().filter(s -> !s.resolved()).forEach(s -> players.add(s.playerId));
        orphans.values().forEach(s -> players.add(s.playerId));
        return players;
    }

    /** Ended unresolved sessions dropped because more than {@link #MAX_ORPHANS} waited (for the log). */
    public int droppedOrphans() {
        return droppedOrphans;
    }

    // ===== AFK =====

    /** An activity signal; returns true when it ended the player's AFK state. */
    public boolean activity(UUID playerId, Instant now) {
        Session session = active.get(playerId);
        return session != null && session.afk.activity(now);
    }

    /** {@code /afk}: the new state (true = AFK now), empty without a session. */
    public Optional<Boolean> toggleAfk(UUID playerId, Instant now) {
        Session session = active.get(playerId);
        return session == null ? Optional.empty() : Optional.of(session.afk.toggleManual(now));
    }

    /** Applies the automatic threshold to every session; returns the players who just became AFK. */
    public List<UUID> checkIdle(Instant now) {
        List<UUID> becameAfk = new ArrayList<>();
        for (Session session : active.values()) {
            if (session.afk.advance(now)) {
                becameAfk.add(session.playerId);
            }
        }
        return becameAfk;
    }

    public boolean isAfk(UUID playerId) {
        Session session = active.get(playerId);
        return session != null && session.afk.isAfk();
    }

    public boolean hasSession(UUID playerId) {
        return active.containsKey(playerId);
    }

    /** The user id of the player's open session; empty without a session or while unknown. */
    public OptionalInt userId(UUID playerId) {
        Session session = active.get(playerId);
        return session == null || !session.resolved() ? OptionalInt.empty() : OptionalInt.of(session.userId);
    }

    /** The key of the player's open session (diagnostic telemetry links events to it); null without one. */
    public UUID sessionKey(UUID playerId) {
        Session session = active.get(playerId);
        return session == null ? null : session.sessionKey;
    }

    // ===== facts =====

    /**
     * One movement segment (main thread, per move event - no allocation). The totals are timestamped
     * when they are flushed.
     */
    public void addDistance(UUID playerId, MovementClassifier.Mode mode, double blocks) {
        Session session = active.get(playerId);
        if (session == null || mode == null || !(blocks > 0)) {
            return;
        }
        switch (mode) {
            case FOOT -> session.foot += blocks;
            case SWIM -> {
                session.foot += blocks;
                session.swim += blocks;
            }
            case FLYING -> session.flying += blocks;
            case VEHICLE -> session.vehicle += blocks;
        }
    }

    /** A counter of the player's session (summed per minute in the buffer). */
    public void addCounter(UUID playerId, StatisticsMetric metric, StatisticsContext context, double value, Instant now) {
        value(playerId, metric, context, value, now);
    }

    /** A record of the player's session (maxed per minute in the buffer). */
    public void addRecord(UUID playerId, StatisticsMetric metric, StatisticsContext context, double value, Instant now) {
        value(playerId, metric, context, value, now);
    }

    private void value(UUID playerId, StatisticsMetric metric, StatisticsContext context, double value, Instant now) {
        Objects.requireNonNull(metric, "metric");
        if (metric.input() != StatisticsMetric.Input.COUNTER && metric.input() != StatisticsMetric.Input.RECORD) {
            throw new IllegalArgumentException(metric.key() + " is not a counter or record");
        }
        Session session = active.get(playerId);
        if (session == null) {
            return;
        }
        if (session.resolved()) {
            put(session.userId, metric, context, value, now);
        } else {
            session.heldValues.add(new HeldValue(metric, context, value, now));
        }
    }

    // ===== internals =====

    /** Classified slices and movement of a session → buffer (resolved) or held (unresolved). */
    private void hand(Session session, List<AfkTracker.Slice> slices, Instant now) {
        if (!session.resolved()) {
            session.heldSlices.addAll(slices);
            return;
        }
        for (AfkTracker.Slice slice : slices) {
            buffer.addDuration(duration(session, slice));
        }
        flushMovement(session, now);
    }

    private void release(Session session) {
        buffer.addSession(StatisticsBatch.SessionEntry.start(session.sessionKey, session.userId, session.startedAt));
        for (AfkTracker.Slice slice : session.heldSlices) {
            buffer.addDuration(duration(session, slice));
        }
        session.heldSlices.clear();
        for (HeldValue held : session.heldValues) {
            put(session.userId, held.metric(), held.context(), held.value(), held.at());
        }
        session.heldValues.clear();
        flushMovement(session, session.endedAt != null ? session.endedAt : session.startedAt);
    }

    private void flushMovement(Session session, Instant at) {
        if (session.foot > 0) {
            buffer.addCounter(session.userId, StatisticsMetric.DISTANCE_FOOT, StatisticsContext.NONE, session.foot, at);
        }
        if (session.swim > 0) {
            buffer.addCounter(session.userId, StatisticsMetric.DISTANCE_SWIM, StatisticsContext.NONE, session.swim, at);
        }
        if (session.flying > 0) {
            buffer.addCounter(session.userId, StatisticsMetric.DISTANCE_FLYING, StatisticsContext.NONE, session.flying, at);
        }
        if (session.vehicle > 0) {
            buffer.addCounter(session.userId, StatisticsMetric.DISTANCE_VEHICLE, StatisticsContext.NONE, session.vehicle, at);
        }
        session.foot = 0;
        session.swim = 0;
        session.flying = 0;
        session.vehicle = 0;
    }

    private void put(int userId, StatisticsMetric metric, StatisticsContext context, double value, Instant at) {
        switch (metric.input()) {
            case COUNTER -> buffer.addCounter(userId, metric, context, value, at);
            case RECORD -> buffer.addRecord(userId, metric, context, value, at);
            default -> throw new IllegalArgumentException(metric.key() + " is not a counter or record");
        }
    }

    private static StatisticsBatch.DurationEntry duration(Session session, AfkTracker.Slice slice) {
        return new StatisticsBatch.DurationEntry(session.sessionKey, session.userId,
                slice.afk() ? StatisticsMetric.AFK_TIME.key() : StatisticsMetric.ACTIVE_PLAYTIME.key(),
                slice.from(), slice.to());
    }
}
