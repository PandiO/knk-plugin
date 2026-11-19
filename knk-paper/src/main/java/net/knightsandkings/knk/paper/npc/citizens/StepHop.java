package net.knightsandkings.knk.paper.npc.citizens;

import java.util.Collection;

import org.bukkit.util.BoundingBox;

/**
 * The engine's stair hop (link J1, PandiO/knk-workspace#695): Minecraft's move control, which Citizens uses for
 * player NPCs, jumps whenever the block at the feet has a collision top above them, besides jumping for a target more
 * than a step above. On a stair's lower half that block is the stair itself: the body has just stepped up half a
 * block, and the hop lifts it a further 0.4 or so before it lands on the upper half. The developer saw it as "already
 * walked up a stair block and then seemed to jump up a bit". When that top is at most the body's step height above the
 * feet, the body walks up onto it by itself (a player's step height is 0.6, a stair's upper half is 0.5 up), so the
 * hop is not needed. Pure; the Bukkit side reads the block and the step height.
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
}
