package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.roads.build.PassabilityRules;

import java.util.Objects;
import java.util.Set;

/**
 * What the walk search needs to know about a block beyond {@code SurfaceGrid} (KNG-51
 * {@code LAST_MILE_PATHFINDING.md} §4): doors, climbables and water. The road builder never asks
 * these, which is why {@code CompactSpans} does not store them; the walk capture (Phase B) must
 * record them for the search box — {@link #ofMaterials} is the reference answer over material
 * names, a capture may store the three flags instead.
 *
 * <p>All coordinates are block coordinates; implementations must be safe to read off the main
 * thread (they are captured data, never a live world).
 */
public interface WalkCells {

    /** No doors, climbables or water anywhere (the road builder's view). */
    WalkCells NONE = new WalkCells() {
        @Override
        public boolean isDoor(int x, int y, int z) {
            return false;
        }

        @Override
        public boolean isClimbable(int x, int y, int z) {
            return false;
        }

        @Override
        public boolean isWater(int x, int y, int z) {
            return false;
        }
    };

    /**
     * A hand-openable door or fence gate block, open or closed ({@link PassabilityRules#isHandOpenableDoor}).
     * Its block counts as passable headroom; whether the mover may pass is the cell's access verdict.
     */
    boolean isDoor(int x, int y, int z);

    /**
     * A block the mover climbs (a ladder; {@link MovementProfile#climbables}; an open trapdoor over one,
     * {@link #isClimbableHatch}). Never a floor, always passable.
     */
    boolean isClimbable(int x, int y, int z);

    /** Water at this block (a wading cell when it is the feet block). */
    boolean isWater(int x, int y, int z);

    /** A block's material name ({@code Material.name()} spelling; {@code AIR} for air). */
    @FunctionalInterface
    interface MaterialLookup {
        String material(int x, int y, int z);
    }

    /** Whether the block at a position is open (a trapdoor's {@code open} state); block state, not material. */
    @FunctionalInterface
    interface OpenLookup {
        /** Nothing is open (material names only, as before PandiO/knk-workspace#771). */
        OpenLookup NONE = (x, y, z) -> false;

        boolean isOpen(int x, int y, int z);
    }

    /**
     * The hatch rule (PandiO/knk-workspace#771 item 2): an <em>open</em> trapdoor directly above a climbable block is
     * climbed like the ladder below it, as a player climbs through a hatch onto a roof. It is then passable and never a
     * floor, so the ladder column reaches the roof (up and down). A closed trapdoor, and an open one with no climbable
     * below, stay what their material makes them (solid: stood on, never passed). The trapdoor's facing is not asked.
     *
     * @param material      the block's material name
     * @param open          the block's {@code open} state
     * @param materialBelow the material of the block directly below
     * @param climbables    the profile's climbable materials
     */
    static boolean isClimbableHatch(String material, boolean open, String materialBelow, Set<String> climbables) {
        return open && PassabilityRules.isTrapdoor(material) && materialBelow != null
            && climbables.contains(materialBelow);
    }

    /** The flags derived from material names: doors by {@link PassabilityRules}, the profile's climbables, water. */
    static WalkCells ofMaterials(MaterialLookup lookup, Set<String> climbables) {
        return ofMaterials(lookup, climbables, OpenLookup.NONE);
    }

    /**
     * As {@link #ofMaterials(MaterialLookup, Set)}, with the open state of trapdoors: an open trapdoor over a climbable
     * is climbable too ({@link #isClimbableHatch}).
     */
    static WalkCells ofMaterials(MaterialLookup lookup, Set<String> climbables, OpenLookup open) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(open, "open");
        Set<String> climb = Set.copyOf(Objects.requireNonNull(climbables, "climbables"));
        return new WalkCells() {
            @Override
            public boolean isDoor(int x, int y, int z) {
                return PassabilityRules.isHandOpenableDoor(lookup.material(x, y, z));
            }

            @Override
            public boolean isClimbable(int x, int y, int z) {
                String material = lookup.material(x, y, z);
                return climb.contains(material) || PassabilityRules.isTrapdoor(material)
                    && isClimbableHatch(material, open.isOpen(x, y, z), lookup.material(x, y - 1, z), climb);
            }

            @Override
            public boolean isWater(int x, int y, int z) {
                return PassabilityRules.isWater(lookup.material(x, y, z));
            }
        };
    }
}
