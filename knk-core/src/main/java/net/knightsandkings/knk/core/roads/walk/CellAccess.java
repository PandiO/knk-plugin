package net.knightsandkings.knk.core.roads.walk;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Access at cell level (KNG-51 {@code LAST_MILE_PATHFINDING.md} §6): the cell-level analogue of the
 * road router's {@code AccessPolicy}. A block search has no edges, so the same rules (gates, denied
 * domains, doors the mover may not open) are answered per cell, built per request on the main
 * thread so the search thread touches no Bukkit (the adapters are Phase B).
 *
 * <p>Cost-or-blocked, not a boolean: {@code 0} free, a finite value = allowed at that extra cost,
 * {@link #BLOCKED} ({@code +∞}) = not walkable. That also expresses "a gate I may breach at a price"
 * for NPC movers later (§13).
 *
 * <p>Coordinates are the block the mover's <b>feet</b> are in: {@code floorY + 1} for a standing
 * cell, the climbable block itself for a ladder cell. The search's own door cost
 * ({@link MovementProfile#doorCost}) is added on top of a finite verdict; the adapter answers only
 * "may pass" — {@code 0} — or {@link #BLOCKED}.
 */
@FunctionalInterface
public interface CellAccess {

    /** Not walkable. */
    double BLOCKED = Double.POSITIVE_INFINITY;

    /** Everything open at no cost. */
    CellAccess OPEN = (x, feetY, z) -> 0.0;

    /** {@code 0} free, finite = allowed at that extra cost, {@link #BLOCKED} = not walkable. Never negative or NaN. */
    double extraCost(int x, int feetY, int z);

    /** Why a cell is blocked, for debugging (empty when it is not blocked). */
    default Optional<String> denyReason(int x, int feetY, int z) {
        return extraCost(x, feetY, z) == BLOCKED ? Optional.of("blocked") : Optional.empty();
    }

    /** Whether a cell can be entered at all. */
    default boolean allows(int x, int feetY, int z) {
        return extraCost(x, feetY, z) != BLOCKED;
    }

    /**
     * Several verdicts together (gates, regions, doors): blocked when any blocks, otherwise the sum
     * of the costs; the deny reason is the first blocking part's.
     */
    static CellAccess all(CellAccess... parts) {
        List<CellAccess> list = List.of(parts);
        list.forEach(p -> Objects.requireNonNull(p, "part"));
        return new CellAccess() {
            @Override
            public double extraCost(int x, int feetY, int z) {
                double sum = 0.0;
                for (CellAccess part : list) {
                    double cost = part.extraCost(x, feetY, z);
                    if (cost == BLOCKED) {
                        return BLOCKED;
                    }
                    sum += cost;
                }
                return sum;
            }

            @Override
            public Optional<String> denyReason(int x, int feetY, int z) {
                for (CellAccess part : list) {
                    Optional<String> reason = part.denyReason(x, feetY, z);
                    if (reason.isPresent()) {
                        return reason;
                    }
                }
                return Optional.empty();
            }
        };
    }
}
