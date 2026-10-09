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

    /** A block the mover climbs (a ladder; {@link MovementProfile#climbables}). Never a floor, always passable. */
    boolean isClimbable(int x, int y, int z);

    /** Water at this block (a wading cell when it is the feet block). */
    boolean isWater(int x, int y, int z);

    /** A block's material name ({@code Material.name()} spelling; {@code AIR} for air). */
    @FunctionalInterface
    interface MaterialLookup {
        String material(int x, int y, int z);
    }

    /** The flags derived from material names: doors by {@link PassabilityRules}, the profile's climbables, water. */
    static WalkCells ofMaterials(MaterialLookup lookup, Set<String> climbables) {
        Objects.requireNonNull(lookup, "lookup");
        Set<String> climb = Set.copyOf(Objects.requireNonNull(climbables, "climbables"));
        return new WalkCells() {
            @Override
            public boolean isDoor(int x, int y, int z) {
                return PassabilityRules.isHandOpenableDoor(lookup.material(x, y, z));
            }

            @Override
            public boolean isClimbable(int x, int y, int z) {
                return climb.contains(lookup.material(x, y, z));
            }

            @Override
            public boolean isWater(int x, int y, int z) {
                return PassabilityRules.isWater(lookup.material(x, y, z));
            }
        };
    }
}
