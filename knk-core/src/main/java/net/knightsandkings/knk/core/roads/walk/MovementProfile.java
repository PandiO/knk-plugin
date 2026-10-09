package net.knightsandkings.knk.core.roads.walk;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a mover can do (KNG-51 {@code LAST_MILE_PATHFINDING.md} §3, §13): clearance, drops, doors,
 * wading and climbing, with the cost of each. The player profile is the only one in v1; the type
 * exists so KNG-36 can add NPC profiles (a taller hitbox, no doors, breaching) without a rewrite.
 *
 * <p>Costs are in "blocks walked": a level orthogonal move costs 1. Every extra is added to the
 * move that enters the cell (§4): a step up without a stair or slab {@code +jumpCost}, a drop of
 * {@code k} blocks {@code +dropPenalty × k}, a door {@code +doorCost}; a cell whose feet block is
 * water multiplies the move by {@code waterFactor}; one block of ladder costs {@code climbCost}.
 *
 * @param headroom    passable blocks a standing cell needs above its floor (player: 2, the road
 *                    builder's {@code SpanGrid.HEADROOM})
 * @param maxDrop     the deepest drop the mover takes (player: 3 = no fall damage); below 2 = no drops
 * @param dropPenalty extra cost per block dropped (decided 2026-10-02: 10, "only when it is the sole way")
 * @param jumpCost    extra cost of a one-block step up onto a full block
 * @param opensDoors  whether hand-openable doors and fence gates are walkable at all (§6 decides per cell)
 * @param doorCost    extra cost of passing a door the mover may open
 * @param wades       whether a cell with water at the feet is walkable
 * @param waterFactor cost multiplier of a move into a wading cell
 * @param climbables  material names that are climbed (default {@code LADDER}; config {@code climbables})
 * @param climbCost   cost per block climbed up or down
 * @param wallCost    extra cost of a cell with a wall (anything not passable at feet or head height)
 *                    among its 8 neighbours: paths keep a block from walls and round corners wider where
 *                    there is room (live test 2026-10-08, finding N7; player 1.0, config {@code wall-cost})
 */
public record MovementProfile(
    int headroom,
    int maxDrop,
    double dropPenalty,
    double jumpCost,
    boolean opensDoors,
    double doorCost,
    boolean wades,
    double waterFactor,
    Set<String> climbables,
    double climbCost,
    double wallCost
) {

    /** A player is just under two blocks tall. */
    public static final int PLAYER_HEADROOM = 2;

    /** The player, with the defaults decided 2026-10-02 (§4, §11). */
    public static final MovementProfile PLAYER = new MovementProfile(
        PLAYER_HEADROOM, 3, 10.0, 1.0, true, 3.0, true, 3.0, Set.of("LADDER"), 2.0, 1.0);

    /** A profile without a wall cost (the geometry fixtures, NPC profiles that do not mind walls). */
    public MovementProfile(int headroom, int maxDrop, double dropPenalty, double jumpCost, boolean opensDoors,
                           double doorCost, boolean wades, double waterFactor, Set<String> climbables, double climbCost) {
        this(headroom, maxDrop, dropPenalty, jumpCost, opensDoors, doorCost, wades, waterFactor, climbables, climbCost, 0.0);
    }

    public MovementProfile {
        if (headroom < 1) {
            throw new IllegalArgumentException("headroom must be at least 1: " + headroom);
        }
        if (maxDrop < 0) {
            throw new IllegalArgumentException("maxDrop must not be negative: " + maxDrop);
        }
        requireCost(dropPenalty, "dropPenalty");
        requireCost(jumpCost, "jumpCost");
        requireCost(doorCost, "doorCost");
        requireCost(wallCost, "wallCost");
        if (!(waterFactor >= 1.0) || Double.isInfinite(waterFactor)) {
            throw new IllegalArgumentException("waterFactor must be a finite number >= 1: " + waterFactor);
        }
        if (!(climbCost >= 1.0) || Double.isInfinite(climbCost)) {
            // >= 1 keeps the search heuristic admissible (one block of height is at least 1)
            throw new IllegalArgumentException("climbCost must be a finite number >= 1: " + climbCost);
        }
        climbables = Objects.requireNonNull(climbables, "climbables").stream()
            .map(name -> name.trim().toUpperCase(Locale.ROOT))
            .filter(name -> !name.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    private static void requireCost(double value, String name) {
        if (!(value >= 0.0) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be a finite number >= 0: " + value);
        }
    }

    /** This profile with other drop settings (config {@code navigation.walk.max-drop}, {@code drop-penalty}). */
    public MovementProfile withDrops(int maxDrop, double dropPenalty) {
        return new MovementProfile(headroom, maxDrop, dropPenalty, jumpCost, opensDoors, doorCost, wades,
            waterFactor, climbables, climbCost, wallCost);
    }

    /** This profile with another wall cost (config {@code navigation.walk.wall-cost}). */
    public MovementProfile withWallCost(double wallCost) {
        return new MovementProfile(headroom, maxDrop, dropPenalty, jumpCost, opensDoors, doorCost, wades,
            waterFactor, climbables, climbCost, wallCost);
    }

    /** This profile with another climbable list (config {@code navigation.walk.climbables}). */
    public MovementProfile withClimbables(Set<String> climbables) {
        return new MovementProfile(headroom, maxDrop, dropPenalty, jumpCost, opensDoors, doorCost, wades,
            waterFactor, climbables, climbCost, wallCost);
    }

    /** Whether a material name is climbed by this mover. */
    public boolean isClimbable(String material) {
        return climbables.contains(material);
    }
}
