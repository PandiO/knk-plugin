package net.knightsandkings.knk.core.npc.nav;

import java.util.Objects;
import java.util.Set;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.roads.walk.WalkCells;

/**
 * Ladders and roof hatches on the NPC side (link H1, PandiO/knk-workspace#779), by material name and open state, so the
 * NPC's own checks agree with the walk search's cells ({@link WalkCells#isClimbable}, {@link WalkCells#isClimbableHatch}):
 * <ol>
 *   <li>A climbable (a ladder) and an open trapdoor directly above one are climbed: never a wall for a body, never a
 *       floor.</li>
 *   <li>A closed trapdoor over a climbable is a hatch an NPC may open, like a door: only when its access profile has
 *       {@code open-doors}, and never the iron one (it needs redstone, as the iron door does). Otherwise it stays
 *       solid, as in the walk search.</li>
 * </ol>
 * Pure; the Bukkit side reads the blocks.
 */
public final class NpcClimbables {

    /** The climbables where no configured list is at hand: the walk profile's default ({@code LADDER}). */
    public static final Set<String> DEFAULT = MovementProfile.PLAYER.climbables();

    private NpcClimbables() {
    }

    /**
     * Whether a body climbs in the block: a climbable, or an open trapdoor directly above one.
     *
     * @param material      the block's material name
     * @param open          the block's {@code open} state (false for a block without one)
     * @param materialBelow the material of the block directly below (null when unknown)
     * @param climbables    the climbable materials
     */
    public static boolean climbable(String material, boolean open, String materialBelow, Set<String> climbables) {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(climbables, "climbables");
        return climbables.contains(material) || WalkCells.isClimbableHatch(material, open, materialBelow, climbables);
    }

    /**
     * Whether the block is a closed hatch over a climbable that a hand opens: a closed trapdoor other than the iron
     * one, directly above a climbable.
     */
    public static boolean closedHatch(String material, boolean open, String materialBelow, Set<String> climbables) {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(climbables, "climbables");
        return !open && PassabilityRules.isTrapdoor(material) && !"IRON_TRAPDOOR".equals(material)
            && materialBelow != null && climbables.contains(materialBelow);
    }

    /**
     * The walk search's cells with closed hatches climbable too, for an NPC whose profile opens doors (the NPC opens
     * the hatch on its way, {@code NpcHatches} on the Paper side). The captured cells already hold every open hatch
     * and every climbable; a closed hatch is a trapdoor other than the iron one ({@code material}) right above a cell
     * the captured cells climb. Its open state is not asked: an open one is climbable in {@code cells} already.
     *
     * @param cells    the captured walk cells
     * @param material the captured material at a block, null where the capture has none (a closed trapdoor under air
     *                 is a captured floor, so its material is known)
     */
    public static WalkCells withClosedHatches(WalkCells cells, MaterialAt material) {
        Objects.requireNonNull(cells, "cells");
        Objects.requireNonNull(material, "material");
        return new WalkCells() {
            @Override
            public boolean isDoor(int x, int y, int z) {
                return cells.isDoor(x, y, z);
            }

            @Override
            public boolean isClimbable(int x, int y, int z) {
                if (cells.isClimbable(x, y, z)) {
                    return true;
                }
                if (!cells.isClimbable(x, y - 1, z)) {
                    return false;
                }
                String m = material.at(x, y, z);
                return m != null && PassabilityRules.isTrapdoor(m) && !"IRON_TRAPDOOR".equals(m);
            }

            @Override
            public boolean isWater(int x, int y, int z) {
                return cells.isWater(x, y, z);
            }
        };
    }

    /**
     * A world that knows where a body climbs (link H1): implemented by the Bukkit {@code NavWorld}. The route line and
     * the waypoint chain keep the walk search's ladder cells with it ({@link #climbCell}, {@link #climbStep});
     * without it (tests, fakes) they treat every cell as before.
     */
    @FunctionalInterface
    public interface Climbs {
        /** Whether a body with its feet in the block climbs there (a ladder, an open hatch over one). */
        boolean climbsAt(String world, int x, int feetY, int z);
    }

    /** Whether the feet position is a climb cell in {@code world} (false for a world that does not know climbing). */
    public static boolean climbCell(Object world, NavPoint feet) {
        return world instanceof Climbs climbs && feet != null
            && climbs.climbsAt(feet.world(), feet.blockX(), feet.blockY(), feet.blockZ());
    }

    /**
     * Whether the step from {@code a} to {@code b} (feet positions) is one of the walk search's ladder moves: one end a
     * climb cell, the other in the same column or orthogonally beside it, at most a block up or down (on and off a
     * ladder, up and down it, and off its top onto the floor beside). Such a step is walked as it is: it is no
     * straight walk on floor, and no bridge makes it one.
     */
    public static boolean climbStep(Object world, NavPoint a, NavPoint b) {
        if (a == null || b == null || !a.world().equals(b.world())) {
            return false;
        }
        int dx = Math.abs(a.blockX() - b.blockX());
        int dz = Math.abs(a.blockZ() - b.blockZ());
        int dy = Math.abs(a.blockY() - b.blockY());
        return dx + dz <= 1 && dy <= 1 && (climbCell(world, a) || climbCell(world, b));
    }

    /** A block's material name (null outside the captured box). */
    @FunctionalInterface
    public interface MaterialAt {
        String at(int x, int y, int z);
    }
}
