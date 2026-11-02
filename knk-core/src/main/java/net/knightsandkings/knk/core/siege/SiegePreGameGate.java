package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.gates.GateSpatialIndex;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * Siege DESIGN §8.5 (PandiO/knk-workspace#213, decided 2026-11-02): non-members keep every siege-area gate's
 * <b>pre-game</b> state. The real world holds the siege state; this class holds the Bukkit-free rules that
 * decide, per door, what a non-member is shown, what happens when they right-click it, and whether a move
 * crosses a door that was closed before the match.
 * <ul>
 *   <li>{@link #viewOf} - which cells get fake blocks (DESIGN §8.5 "Per door, for non-members").</li>
 *   <li>{@link #onClick} - a non-member's right-click (per-player opening / TELEPORT carry / refused).</li>
 *   <li>{@link #crosses} - the server-side crossing check: does a player's hitbox, swept along a move, enter a
 *   blocked cell it didn't already touch at the start?</li>
 * </ul>
 */
public final class SiegePreGameGate {

    private SiegePreGameGate() {}

    /** A door's state before the match (from the lockdown snapshot). A door destroyed before the match counts as open. */
    public enum Pre {
        OPEN,
        CLOSED,
        DESTROYED;

        public static Pre of(boolean opened, boolean destroyed) {
            if (destroyed) return DESTROYED;
            return opened ? OPEN : CLOSED;
        }
    }

    /** A door's real (siege) state now. */
    public enum Real {
        CLOSED,
        OPEN,
        MOVING,
        DESTROYED;

        /** True when some of the door's cells may be really solid (closed frame at rest, or mid-animation). */
        public boolean mayBeSolid() {
            return this == CLOSED || this == MOVING;
        }
    }

    /**
     * What a non-member gets sent for one door.
     *
     * @param fakeClosedFrame send the door's closed-frame cells with their block data (it looks closed)
     * @param hideOpenOnly    send air for open-frame cells that aren't part of the closed frame
     * @param hideClosedOnly  send air for closed-frame cells that aren't part of the open frame
     * @param carryOnSolid    a non-member walking into a really solid, hidden cell is carried across (TELEPORT)
     */
    public record View(boolean fakeClosedFrame, boolean hideOpenOnly, boolean hideClosedOnly, boolean carryOnSolid) {
        public static final View NONE = new View(false, false, false, false);

        public boolean sendsAnything() {
            return fakeClosedFrame || hideOpenOnly || hideClosedOnly;
        }
    }

    /**
     * DESIGN §8.5 table. Fake solid blocks are only ever sent for the pre-game closed frame (a wall the crossing
     * check enforces); everything else that differs is shown as air, so a non-member is never shown a floor
     * (e.g. a lowered drawbridge deck) that isn't really there.
     */
    public static View viewOf(Pre pre, Real real) {
        return switch (pre) {
            case CLOSED -> real == Real.CLOSED ? View.NONE : new View(true, real != Real.DESTROYED, false, false);
            case OPEN -> real.mayBeSolid() ? new View(false, false, true, true) : View.NONE;
            case DESTROYED -> real == Real.DESTROYED ? View.NONE : new View(false, true, true, real.mayBeSolid());
        };
    }

    /** Whether the door's pre-game closed frame is a blocked box for non-members (the crossing check). */
    public static boolean blocks(Pre pre) {
        return pre == Pre.CLOSED;
    }

    /** What a non-member's right-click on a locked door does. */
    public enum Click {
        /** Nothing to do (the door is open both before and now). */
        IGNORE,
        /** Carry them across with the gate's TELEPORT pass-through (the real door is solid). */
        CARRY,
        /** Per-player virtual opening: the real door is open; lift their fakes and the crossing check for a while. */
        OPEN_FOR_PLAYER,
        /** Refused: the door was closed before the match and they may not pass it under the pre-game rules. */
        DENY
    }

    /**
     * @param mayPassPreGame whether the player may pass this door under the pre-game rules (gate admin, or the
     *                       pass-through use node on a door that allowed pass-through before the match)
     */
    public static Click onClick(Pre pre, Real real, boolean mayPassPreGame) {
        if (pre == Pre.CLOSED) {
            if (!mayPassPreGame) return Click.DENY;
            return real.mayBeSolid() ? Click.CARRY : Click.OPEN_FOR_PLAYER;
        }
        return real.mayBeSolid() ? Click.CARRY : Click.IGNORE;
    }

    // ==================== crossing check ====================

    /** Half the width of a player's hitbox. */
    public static final double HALF_WIDTH = 0.3;
    /** Height of a standing player's hitbox. */
    public static final double HEIGHT = 1.8;
    /** Sampling step along a move, in blocks (well under the thinnest 1-block frame). */
    static final double STEP = 0.1;
    /** Shrinks the hitbox a little so touching a cell's face doesn't count as entering it. */
    static final double EPSILON = 1.0e-3;
    /** Longer moves (teleports) are sampled with at most this many steps. */
    static final int MAX_STEPS = 4096;

    /**
     * Does a player's hitbox, moved in a straight line from {@code from} to {@code to}, enter a blocked cell
     * (packed with {@link GateSpatialIndex#packCell}) that it didn't already overlap at the start? Cells the
     * player already stands in are ignored, so someone caught inside a frame can always step out.
     */
    public static boolean crosses(double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
                                  LongPredicate blocked) {
        Set<Long> start = new HashSet<>();
        forEachCell(fromX, fromY, fromZ, cell -> {
            start.add(cell);
            return false;
        });
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = (int) Math.min(MAX_STEPS, Math.max(1, Math.ceil(length / STEP)));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            boolean hit = forEachCell(fromX + dx * t, fromY + dy * t, fromZ + dz * t,
                    cell -> !start.contains(cell) && blocked.test(cell));
            if (hit) return true;
        }
        return false;
    }

    /** Whether a hitbox at this position overlaps any blocked cell. */
    public static boolean overlaps(double x, double y, double z, LongPredicate blocked) {
        return forEachCell(x, y, z, blocked::test);
    }

    private interface CellTest {
        boolean test(long cell);
    }

    /** Runs the test on every block cell the hitbox at (x, y, z) overlaps; true as soon as one returns true. */
    private static boolean forEachCell(double x, double y, double z, CellTest test) {
        int minX = (int) Math.floor(x - HALF_WIDTH + EPSILON);
        int maxX = (int) Math.floor(x + HALF_WIDTH - EPSILON);
        int minY = (int) Math.floor(y + EPSILON);
        int maxY = (int) Math.floor(y + HEIGHT - EPSILON);
        int minZ = (int) Math.floor(z - HALF_WIDTH + EPSILON);
        int maxZ = (int) Math.floor(z + HALF_WIDTH - EPSILON);
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    if (test.test(GateSpatialIndex.packCell(bx, by, bz))) return true;
                }
            }
        }
        return false;
    }
}
