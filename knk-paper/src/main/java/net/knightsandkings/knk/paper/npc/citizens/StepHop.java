package net.knightsandkings.knk.paper.npc.citizens;

import java.util.Collection;

import org.bukkit.util.BoundingBox;

/**
 * The engine's stair hop (link J1, PandiO/knk-workspace#695): Minecraft's move control, which Citizens uses for
 * player NPCs, jumps whenever the block at the feet has a collision top above them, besides jumping for a target more
 * than a step above. On a stair's lower half that block is the stair itself: the body has just stepped up half a
 * block, and the hop lifts it a further 0.4 or so before it lands on the upper half. The developer saw it as "already
 * walked up a stair block and then seemed to jump up a bit". When that top is at most the body's step height above the
 * feet, the body walks up onto it by itself (Citizens gives its NPCs a step height of 1.0; a stair's upper half is 0.5
 * up), so the hop is not needed. The move control's other jump, for a target more than a step above the feet within a block
 * ({@link #jumpsForTarget}), is not needed either when the next cell toward that target is at most a step up
 * ({@link #walksOn}): Citizens moves on to the next waypoint once the body is within the list's margin of the current
 * one, so from beside a stair the next waypoint can already be a block up (the replay's hall entry). A full block in the
 * way keeps its jump. Pure; the Bukkit side reads the blocks, the target and the step height.
 */
final class StepHop {

    /** Below this the feet stand on the top already (floating-point slack). */
    static final double ON_TOP = 1e-3;

    private StepHop() {
    }

    /**
     * Whether a jump is only the move control's hop inside a block the body can step onto: the block at the feet has
     * a collision top above them, at most {@code stepHeight} above.
     *
     * @param feetY      the body's feet height
     * @param blockY     the y of the block the feet are in
     * @param boxes      that block's collision boxes, block-relative (0 to 1, a fence's up to 1.5)
     * @param stepHeight how far the body steps up without a jump (a player's 0.6)
     */
    static boolean isStepHop(double feetY, int blockY, Collection<BoundingBox> boxes, double stepHeight) {
        if (boxes == null || boxes.isEmpty()) {
            return false;
        }
        double top = Double.NEGATIVE_INFINITY;
        for (BoundingBox box : boxes) {
            top = Math.max(top, box.getMaxY());
        }
        double above = blockY + top - feetY;
        return above > ON_TOP && above <= stepHeight + 1e-6;
    }

    /**
     * Whether the move control also jumps for its target: one at least {@code stepHeight} above the feet and less than
     * a block away across (the squared distance under {@code reach}, the larger of 1 and the body's width), as
     * Citizens' move control decides ({@code >=}, where vanilla's has {@code >}).
     *
     * @param dx         target x minus the body's x
     * @param dy         target y minus the feet
     * @param dz         target z minus the body's z
     * @param stepHeight the body's step height
     * @param reach      {@code max(1, body width)}
     */
    static boolean jumpsForTarget(double dx, double dy, double dz, double stepHeight, double reach) {
        return dy >= stepHeight - 1e-9 && dx * dx + dz * dz < reach;
    }

    /**
     * The surface a body walking at {@code feetY} meets in the next cell toward its target, from that cell's blocks:
     * the block the feet are level with ({@code atTop}), the one above it ({@code aboveTop}) and the one below
     * ({@code belowTop}), each the absolute top of its step (NaN without a collision box). A block above means a rise
     * of more than a block; without any, the cell drops.
     */
    static double nextSurface(double feetY, double belowTop, double atTop, double aboveTop) {
        if (!Double.isNaN(aboveTop)) {
            return aboveTop;
        }
        if (!Double.isNaN(atTop)) {
            return atTop;
        }
        return Double.isNaN(belowTop) ? feetY - 1 : belowTop;
    }

    /**
     * Whether the body walks on toward its target without a jump: the next cell's surface ({@link #nextSurface}) is at
     * most a step above the feet. Then a jump the move control makes for that target is not needed either: from beside
     * a stair the body steps onto the stair's lower half, and from there onto the next.
     */
    static boolean walksOn(double feetY, double nextSurface, double stepHeight) {
        return nextSurface - feetY <= stepHeight + 1e-6;
    }
}
