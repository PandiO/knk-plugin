package net.knightsandkings.knk.core.roads.walk;

import java.util.Objects;

/**
 * One walk search (KNG-51 {@code LAST_MILE_PATHFINDING.md} §3, §5): where the mover is, where to,
 * over which captured terrain, with whose abilities and access, within which budget. Immutable;
 * everything in it must be safe to read off the main thread.
 *
 * @param terrain the captured world around the leg
 * @param access  per-cell verdicts for this mover ({@link CellAccess#OPEN} for none)
 * @param profile what the mover can do
 * @param startX  the mover's feet position (a player's location; feet may be mid-air, on a ladder, in water)
 * @param startY  feet y
 * @param startZ  feet z
 * @param targetX the target's floor position (the point the goal is measured from and the heuristic aims at)
 * @param targetFloorY floor y of the target (feet at {@code + 1}), the navigation network's convention
 * @param targetZ target z
 * @param goal    when a cell counts as arrived
 * @param budget  the search limits
 * @param weights preference multipliers on walk cells (off-road phase 5.2, #709); {@link WalkCellWeights#NONE} for
 *                none, which every request without them gets
 */
public record WalkRequest(
    WalkTerrain terrain,
    CellAccess access,
    MovementProfile profile,
    double startX,
    double startY,
    double startZ,
    double targetX,
    double targetFloorY,
    double targetZ,
    WalkGoal goal,
    WalkBudget budget,
    WalkCellWeights weights
) {

    public WalkRequest {
        Objects.requireNonNull(terrain, "terrain");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(budget, "budget");
        weights = weights == null ? WalkCellWeights.NONE : weights;
        requireFinite(startX, startY, startZ, targetX, targetFloorY, targetZ);
    }

    /** A request without cell weights (as before phase 5.2). */
    public WalkRequest(WalkTerrain terrain, CellAccess access, MovementProfile profile, double startX, double startY,
                       double startZ, double targetX, double targetFloorY, double targetZ, WalkGoal goal,
                       WalkBudget budget) {
        this(terrain, access, profile, startX, startY, startZ, targetX, targetFloorY, targetZ, goal, budget,
            WalkCellWeights.NONE);
    }

    private static void requireFinite(double... values) {
        for (double v : values) {
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("coordinates must be finite");
            }
        }
    }

    /**
     * A request to a target floor point with the usual point goal ({@link WalkGoal#within}
     * {@code arriveDistance}), the player profile, no access rules and the default budget.
     */
    public static WalkRequest toPoint(WalkTerrain terrain, double startX, double startY, double startZ,
                                      double targetX, double targetFloorY, double targetZ, double arriveDistance) {
        return new WalkRequest(terrain, CellAccess.OPEN, MovementProfile.PLAYER, startX, startY, startZ,
            targetX, targetFloorY, targetZ, WalkGoal.within(targetX, targetFloorY, targetZ, arriveDistance),
            WalkBudget.DEFAULTS);
    }

    public WalkRequest withAccess(CellAccess access) {
        return new WalkRequest(terrain, access, profile, startX, startY, startZ, targetX, targetFloorY, targetZ,
            goal, budget, weights);
    }

    public WalkRequest withProfile(MovementProfile profile) {
        return new WalkRequest(terrain, access, profile, startX, startY, startZ, targetX, targetFloorY, targetZ,
            goal, budget, weights);
    }

    public WalkRequest withGoal(WalkGoal goal) {
        return new WalkRequest(terrain, access, profile, startX, startY, startZ, targetX, targetFloorY, targetZ,
            goal, budget, weights);
    }

    public WalkRequest withBudget(WalkBudget budget) {
        return new WalkRequest(terrain, access, profile, startX, startY, startZ, targetX, targetFloorY, targetZ,
            goal, budget, weights);
    }

    /** The same request with these cell weights (off-road phase 5.2, #709); null means none. */
    public WalkRequest withWeights(WalkCellWeights weights) {
        return new WalkRequest(terrain, access, profile, startX, startY, startZ, targetX, targetFloorY, targetZ,
            goal, budget, weights);
    }

    /**
     * The box every cell of an accepted path lies in, as {@code {minX, minY, minZ, maxX, maxY, maxZ}} (block
     * coordinates, feet y): a path is at most {@link WalkBudget#lengthCap(double, double) the length cap} long, so its
     * cells lie inside the ellipsoid with the start and target as foci, whose half width is
     * {@code sqrt((cap / 2)^2 - (straight / 2)^2)}; plus the start and goal snap and one block. Used to keep only the
     * weighted areas a search can touch (phase 5.2).
     */
    public int[] reachBox() {
        double straight = straightDistance();
        double cap = budget.lengthCap(straight, heightDifference());
        double half = cap / 2;
        double slack = Math.sqrt(Math.max(0.0, half * half - straight * straight / 4))
            + Math.max(budget.startSnap(), budget.goalSnap()) + 1;
        double startFeetY = startY;
        double targetFeetY = targetFloorY + 1;
        return new int[] {
            (int) Math.floor(Math.min(startX, targetX) - slack),
            (int) Math.floor(Math.min(startFeetY, targetFeetY) - slack),
            (int) Math.floor(Math.min(startZ, targetZ) - slack),
            (int) Math.ceil(Math.max(startX, targetX) + slack),
            (int) Math.ceil(Math.max(startFeetY, targetFeetY) + slack),
            (int) Math.ceil(Math.max(startZ, targetZ) + slack)};
    }

    /** Straight 3D distance from the start (as a floor point) to the target. */
    public double straightDistance() {
        double dx = targetX - startX;
        double dy = targetFloorY - (startY - 1);
        double dz = targetZ - startZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Height between the start (as a floor point) and the target, up or down: the length cap's climb (KNG-108). */
    public double heightDifference() {
        return Math.abs(targetFloorY - (startY - 1));
    }
}
