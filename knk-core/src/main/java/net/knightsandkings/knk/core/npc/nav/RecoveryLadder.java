package net.knightsandkings.knk.core.npc.nav;

import java.util.Locale;
import java.util.function.Predicate;

import net.knightsandkings.knk.core.navigation.shape.NavGeometry;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;

/**
 * One NPC's stuck spells and its recovery ladder (implementation plan 4b.2 items 6 and 7; BEHAVIOR_MODEL NAV-20): what
 * to try next, when, how many tries are left, and how a spell is counted and logged. It decides; the caller moves the
 * NPC. Pure state, driven by the caller's clock (ticks); main thread; one per NPC.
 *
 * <p>A spell starts with the first stuck report (the engine's stationary check, an engine path search that found
 * nothing, or the progress watchdog); later reports join it. The NPC then takes the steps in order, one per try:
 * {@link Step#BACK_OFF}, {@link Step#SIDE_STEP}, {@link Step#LOCAL_LEG}, and when those are used up (they count against
 * {@code max-retries}) {@link Step#REPLAN}. The first try starts at once; between two tries the NPC waits
 * {@code recovery-wait-ticks}. A step that cannot be taken is skipped without a wait and not counted. A try has worked
 * when the NPC came at least {@link #FREED_BLOCKS} further along its path within {@code no-progress-ticks} of the try's
 * start; the spell ends there, freed by that step. A new stuck within {@link #CONTINUE_BLOCKS} of the last spell's
 * spot within {@link #CONTINUE_TICKS} continues that spell's ladder where it stopped.
 */
public final class RecoveryLadder {

    /** A try worked when the NPC came this many blocks further along its path. */
    public static final double FREED_BLOCKS = 1;
    /** A new stuck this close to the last spell's spot ... */
    public static final double CONTINUE_BLOCKS = 2;
    /** ... within this many ticks (30 s) continues its ladder. */
    public static final long CONTINUE_TICKS = 600;

    /** The steps, in ladder order. */
    public enum Step {
        BACK_OFF("back-off", Event.FREED_BACK_OFF),
        SIDE_STEP("side-step", Event.FREED_SIDE_STEP),
        LOCAL_LEG("local leg", Event.FREED_LOCAL_LEG),
        REPLAN("replan", Event.FREED_REPLAN);

        private final String label;
        private final Event freed;

        Step(String label, Event freed) {
            this.label = label;
            this.freed = freed;
        }

        public String label() {
            return label;
        }

        /** The event that counts a spell freed by this step. */
        public Event freedEvent() {
            return freed;
        }
    }

    /** What reported the stuck. */
    public enum Reporter {
        /** The engine's stationary check. */
        STATIONARY,
        /** The engine's path search found nothing. */
        NO_PATH,
        /** K&K's progress watchdog. */
        NO_PROGRESS;

        boolean engine() {
            return this != NO_PROGRESS;
        }
    }

    /** What a stuck report did to the ladder. */
    public enum Report {
        /** A new spell started (count it, log it, take the first step now). */
        STARTED,
        /** A running try failed (wait {@code recovery-wait-ticks}, then the next step). */
        TRY_FAILED,
        /** It joined the open spell and changed nothing (the NPC is between two tries). */
        JOINED
    }

    private static final Step[] COUNTED = {Step.BACK_OFF, Step.SIDE_STEP, Step.LOCAL_LEG};

    // ---- the open spell ----
    private boolean open;
    private NavPoint spot;
    private long startTick;
    private Reporter reporter;
    private boolean nearWall;
    private int next;
    private int used;
    private int taken;
    private Step current;
    private boolean tryRunning;
    private long tryStartTick;
    private long nextTryTick;
    // ---- the last spell, for continuing its ladder ----
    private NavPoint lastSpot;
    private long lastEndTick = Long.MIN_VALUE;
    private int lastNext;
    private int lastUsed;

    public boolean isOpen() {
        return open;
    }

    /** A try runs now (the NPC moves on a step and its outcome is not known yet). */
    public boolean tryRunning() {
        return open && tryRunning;
    }

    /** The step of the running or last try of the open spell, or null. */
    public Step current() {
        return current;
    }

    public NavPoint spot() {
        return spot;
    }

    public long startTick() {
        return startTick;
    }

    public long tryStartTick() {
        return tryStartTick;
    }

    /** The tick the next try may start (after a failed one). */
    public long nextTryTick() {
        return nextTryTick;
    }

    public boolean nearWall() {
        return nearWall;
    }

    public Reporter reporter() {
        return reporter;
    }

    /** Steps counted against the cap in the open spell. */
    public int used() {
        return used;
    }

    /** Steps taken in the open spell, the replans included. */
    public int taken() {
        return taken;
    }

    /**
     * A stuck report.
     *
     * @param waitTicks {@code recovery-wait-ticks}, the wait before the next try when this ends a running one
     */
    public Report report(long tick, NavPoint at, Reporter by, boolean wallNear, int waitTicks) {
        if (open) {
            if (tryRunning) {
                tryRunning = false;
                nextTryTick = tick + Math.max(0, waitTicks);
                return Report.TRY_FAILED;
            }
            return Report.JOINED;
        }
        open = true;
        spot = at;
        startTick = tick;
        reporter = by;
        nearWall = wallNear;
        taken = 0;
        current = null;
        tryRunning = false;
        nextTryTick = tick;
        if (lastSpot != null && at != null && tick - lastEndTick <= CONTINUE_TICKS
            && lastSpot.world().equals(at.world()) && Math.sqrt(sq(lastSpot.x() - at.x()) + sq(lastSpot.y() - at.y())
            + sq(lastSpot.z() - at.z())) <= CONTINUE_BLOCKS) {
            next = lastNext;
            used = lastUsed;
        } else {
            next = 0;
            used = 0;
        }
        return Report.STARTED;
    }

    /** The cap on counted steps: {@code max-retries}, at most the three steps there are. */
    public static int cap(int maxRetries) {
        return Math.max(0, Math.min(maxRetries, COUNTED.length));
    }

    /**
     * The step of the next try (the spell must be open and no try running): the next of back-off, side-step and local
     * leg that {@code possible} allows while fewer than {@link #cap} were used, else {@link Step#REPLAN}. Steps
     * {@code possible} refuses are skipped and not counted. The try starts now.
     */
    public Step nextStep(long tick, int maxRetries, Predicate<Step> possible) {
        if (!open) {
            throw new IllegalStateException("no stuck spell is open");
        }
        Step step = Step.REPLAN;
        int cap = cap(maxRetries);
        while (used < cap && next < COUNTED.length) {
            Step candidate = COUNTED[next++];
            if (possible.test(candidate)) {
                used++;
                step = candidate;
                break;
            }
        }
        current = step;
        taken++;
        tryRunning = true;
        tryStartTick = tick;
        return step;
    }

    /** The try has not really started yet (the NPC waits for a plan or stands still): its clock starts later. */
    public void holdTryClock(long tick) {
        tryStartTick = tick;
    }

    /** The running try failed without a stuck report (no progress within its time): the next one after the wait. */
    public void tryFailed(long tick, int waitTicks) {
        tryRunning = false;
        nextTryTick = tick + Math.max(0, waitTicks);
    }

    /**
     * The spell ends, freed by the step of the last try. Returns the step's freed event (or {@link Event#FREED_REPLAN}
     * when no step was taken yet: the route the NPC had got it free).
     */
    public Event freed(long tick, NavPoint at) {
        Event event = current == null ? Event.FREED_REPLAN : current.freedEvent();
        close(tick, at);
        return event;
    }

    /** The spell ends without the NPC getting free. */
    public void notFreed(long tick, NavPoint at) {
        close(tick, at);
    }

    private void close(long tick, NavPoint at) {
        if (!open) {
            return;
        }
        open = false;
        tryRunning = false;
        lastSpot = spot != null ? spot : at; // where it got stuck, not where it got free
        lastEndTick = tick;
        lastNext = next;
        lastUsed = used;
        current = null;
    }

    /** Forgets the last spell too (the NPC was removed or teleported). */
    public void reset() {
        open = false;
        tryRunning = false;
        current = null;
        lastSpot = null;
        lastEndTick = Long.MIN_VALUE;
    }

    // ==================== log lines (implementation plan 4b.2 item 7.5) ====================

    /** "stationary", "no progress 40 ticks" or "engine found no path". */
    public static String what(Reporter by, int noProgressTicks) {
        return switch (by) {
            case STATIONARY -> "stationary";
            case NO_PATH -> "engine found no path";
            case NO_PROGRESS -> "no progress " + noProgressTicks + " ticks";
        };
    }

    /**
     * {@code <npc> stuck (stationary) at x y z, wall at x y z (north): step 1 of 3 back-off}; the count never passes
     * the cap.
     */
    public String stepLine(String npc, String what, NavPoint at, RecoveryMoves.Wall wall, int maxRetries, String detail) {
        String where = NavGeometry.block(at);
        String wallText = wall == null ? "no wall next to it" : "wall at " + wall;
        if (current == Step.REPLAN) {
            return String.format(Locale.ROOT, "%s not freed at %s: replanning (steps used up)", npc, where);
        }
        return String.format(Locale.ROOT, "%s stuck (%s) at %s, %s: step %d of %d %s%s", npc, what, where, wallText,
            used, cap(maxRetries), current.label(), detail == null ? "" : " " + detail);
    }

    /** {@code <npc> freed at x y z by side-step (2 steps, 1.4 s)}. */
    public static String freedLine(String npc, NavPoint at, Step by, int steps, long ticks) {
        return String.format(Locale.ROOT, "%s freed at %s by %s (%d step%s, %.1f s)", npc, NavGeometry.block(at),
            by == null ? "its route" : by.label(), steps, steps == 1 ? "" : "s", ticks / 20.0);
    }

    /** {@code <npc> not freed at x y z: <why>}. */
    public static String notFreedLine(String npc, NavPoint at, String why) {
        return String.format(Locale.ROOT, "%s not freed at %s: %s", npc, NavGeometry.block(at), why);
    }

    private static double sq(double v) {
        return v * v;
    }
}
