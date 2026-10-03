package net.knightsandkings.knk.core.statistics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One online player's active/AFK classification (DESIGN.md §F.2, L1-1). Pure and single-threaded
 * (the caller owns it on the server main thread).
 * <ul>
 *   <li><b>Automatic AFK:</b> after {@code idle} without an activity signal. The idle window that led
 *   to it is AFK time too (retroactive): seconds since the last activity stay <em>pending</em> until
 *   classified - activity within the threshold makes them active, reaching the threshold makes them
 *   AFK from the last activity on.</li>
 *   <li><b>Manual AFK</b> ({@link #toggleManual}): AFK from that instant; the pending window before it
 *   counts as active.</li>
 *   <li>Any activity ends AFK (automatic or manual).</li>
 *   <li>{@link #quit}: pending time counts as active (the player was not AFK yet).</li>
 * </ul>
 * {@link #accrue} hands out the classified time as {@link Slice}s ({@code [from, to)}), each at most
 * {@link #MAX_SLICE} long (the API's per-entry limit); pending time stays until it is classified.
 * Instants passed in never go backwards in practice; an earlier instant is treated as "now unchanged".
 */
public final class AfkTracker {

    /** The API accepts at most a day per duration entry. */
    public static final Duration MAX_SLICE = Duration.ofSeconds(86_400);

    /** A classified interval {@code [from, to)}. */
    public record Slice(Instant from, Instant to, boolean afk) {
        public Slice {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }

        public Duration length() {
            return Duration.between(from, to);
        }
    }

    private final Duration idle;
    /** Everything before this instant was handed out (or is queued in {@link #ready}). */
    private Instant classifiedUntil;
    private Instant lastActivity;
    private boolean afk;
    private boolean manual;
    private Instant afkSince;
    private final List<Slice> ready = new ArrayList<>();

    /**
     * @param start the session start (the first instant of classified time)
     * @param idle  the automatic-AFK threshold; zero or negative = no automatic AFK
     */
    public AfkTracker(Instant start, Duration idle) {
        this.classifiedUntil = Objects.requireNonNull(start, "start");
        this.lastActivity = start;
        this.idle = idle == null || idle.isNegative() ? Duration.ZERO : idle;
    }

    public boolean isAfk() {
        return afk;
    }

    /** True while the current AFK state was entered with {@code /afk}. */
    public boolean isManual() {
        return afk && manual;
    }

    /** When the current AFK state began (retroactively for automatic AFK); null while active. */
    public Instant afkSince() {
        return afk ? afkSince : null;
    }

    public Instant lastActivity() {
        return lastActivity;
    }

    /**
     * Checks the automatic threshold at {@code now}: crossing it makes the player AFK from their last
     * activity on. Returns true when this call made them AFK.
     */
    public boolean advance(Instant now) {
        if (afk || idle.isZero() || now == null) {
            return false;
        }
        if (Duration.between(lastActivity, now).compareTo(idle) < 0) {
            return false;
        }
        emit(classifiedUntil, lastActivity, false);
        classifiedUntil = later(classifiedUntil, lastActivity);
        afk = true;
        manual = false;
        afkSince = lastActivity;
        return true;
    }

    /**
     * An activity signal at {@code now}. Ends AFK (automatic or manual); returns true when the player
     * was AFK - by then possibly only because the threshold was crossed since the last check.
     */
    public boolean activity(Instant now) {
        advance(now);
        Instant at = later(lastActivity, now);
        boolean wasAfk = afk;
        if (afk) {
            emit(classifiedUntil, at, true);
            classifiedUntil = later(classifiedUntil, at);
            afk = false;
            manual = false;
            afkSince = null;
        }
        lastActivity = at;
        return wasAfk;
    }

    /**
     * {@code /afk}: leaves AFK when AFK (like an activity), otherwise becomes AFK right now - the
     * pending window before counts as active. Returns the new state (true = now AFK).
     */
    public boolean toggleManual(Instant now) {
        advance(now);
        if (afk) {
            activity(now);
            return false;
        }
        Instant at = later(classifiedUntil, now);
        emit(classifiedUntil, at, false);
        classifiedUntil = at;
        lastActivity = later(lastActivity, at);
        afk = true;
        manual = true;
        afkSince = at;
        return true;
    }

    /**
     * Hands out the time classified up to {@code now}: AFK time up to now while AFK, otherwise active
     * time up to the last activity (the window since then stays pending).
     */
    public List<Slice> accrue(Instant now) {
        advance(now);
        if (afk) {
            Instant at = later(classifiedUntil, now);
            emit(classifiedUntil, at, true);
            classifiedUntil = at;
        } else if (lastActivity.isAfter(classifiedUntil)) {
            emit(classifiedUntil, lastActivity, false);
            classifiedUntil = lastActivity;
        }
        return drain();
    }

    /** The session ends at {@code now}: everything left is classified (pending time as active) and handed out. */
    public List<Slice> quit(Instant now) {
        advance(now);
        Instant at = later(classifiedUntil, now);
        emit(classifiedUntil, at, afk);
        classifiedUntil = at;
        return drain();
    }

    private List<Slice> drain() {
        List<Slice> out = List.copyOf(ready);
        ready.clear();
        return out;
    }

    /** Queues {@code [from, to)} (merged with an adjacent slice of the same kind, split at {@link #MAX_SLICE}). */
    private void emit(Instant from, Instant to, boolean afkSlice) {
        if (!to.isAfter(from)) {
            return;
        }
        Instant start = from;
        if (!ready.isEmpty()) {
            Slice last = ready.get(ready.size() - 1);
            if (last.afk() == afkSlice && last.to().equals(from) && last.length().compareTo(MAX_SLICE) < 0) {
                ready.remove(ready.size() - 1);
                start = last.from();
            }
        }
        while (Duration.between(start, to).compareTo(MAX_SLICE) > 0) {
            Instant cut = start.plus(MAX_SLICE);
            ready.add(new Slice(start, cut, afkSlice));
            start = cut;
        }
        ready.add(new Slice(start, to, afkSlice));
    }

    private static Instant later(Instant a, Instant b) {
        return b == null || a.isAfter(b) ? a : b;
    }
}
