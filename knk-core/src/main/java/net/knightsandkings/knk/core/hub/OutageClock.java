package net.knightsandkings.knk.core.hub;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The outage delay timer (hub DESIGN §10, §13; developer decision 2026-10-12). The timer starts at the first drop of an
 * outage and resets only after a full recovery: the API is back and the server has synced its backlog (every durable
 * spool empty), or the API has been back for {@link #RECOVERY_CAP}, so a spool stuck on a bad record can't block the
 * reset forever. A drop before that continues the same timer. The start is persisted, so a restart during an outage
 * continues it too.
 * <p>
 * Outage actions run only while the API is down; the timer keeps counting while it recovers. Not thread-safe: the
 * caller confines it to one thread (the main thread).
 */
public final class OutageClock {

    public static final Duration RECOVERY_CAP = Duration.ofMinutes(5);

    public enum Phase {
        /** No outage, or a full recovery since the last one. */
        HEALTHY,
        /** The API is down. */
        OUTAGE,
        /** The API is back, but the backlog is not synced yet: a drop now continues the timer. */
        RECOVERING
    }

    private Phase phase = Phase.HEALTHY;
    private Instant startedAt;
    private Instant upSince;

    /** Restores a persisted outage start (or none); the next {@link #down}/{@link #up} decides the phase. */
    public OutageClock(Instant persistedStart) {
        this.startedAt = persistedStart;
        this.phase = persistedStart == null ? Phase.HEALTHY : Phase.RECOVERING;
    }

    public Phase phase() {
        return phase;
    }

    /** When the current outage began; empty while healthy. */
    public Optional<Instant> startedAt() {
        return Optional.ofNullable(phase == Phase.HEALTHY ? null : startedAt);
    }

    /** The API went down. Returns whether a new outage started (its start needs persisting). */
    public boolean down(Instant now) {
        if (phase == Phase.OUTAGE) {
            return false;
        }
        upSince = null;
        boolean started = phase == Phase.HEALTHY || startedAt == null;
        if (started) {
            startedAt = now;
        }
        phase = Phase.OUTAGE;
        return started;
    }

    /** The API came back. The timer is kept until {@link #tick} sees a full recovery. */
    public void up(Instant now) {
        if (phase == Phase.OUTAGE || (phase == Phase.RECOVERING && upSince == null)) {
            phase = Phase.RECOVERING;
            upSince = now;
        }
    }

    /**
     * Checks for a full recovery. Returns true when the timer was reset now (the persisted start must be cleared).
     *
     * @param synced every durable spool is empty
     */
    public boolean tick(Instant now, boolean synced) {
        if (phase != Phase.RECOVERING || upSince == null) {
            return false;
        }
        if (synced || !now.isBefore(upSince.plus(RECOVERY_CAP))) {
            phase = Phase.HEALTHY;
            startedAt = null;
            upSince = null;
            return true;
        }
        return false;
    }

    /** Whether outage actions may run: the API is down. */
    public boolean inOutage() {
        return phase == Phase.OUTAGE;
    }

    /** Whether an action with this delay is due now: in an outage that began at least {@code delaySeconds} ago. */
    public boolean due(Instant now, int delaySeconds) {
        return inOutage() && startedAt != null && !now.isBefore(startedAt.plusSeconds(Math.max(0, delaySeconds)));
    }
}
