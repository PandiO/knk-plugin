package net.knightsandkings.knk.core.roads.build;

import java.util.OptionalInt;

/**
 * Which blocks belong to a gate door's <em>closed</em> footprint (DESIGN §5.4, plan D9). Prebuilt by
 * Phase 3 from {@code GateManager.closedFootprint(gateId)} for every door before the build starts;
 * the builder itself never touches {@code GateManager}.
 *
 * <p>A block in a footprint counts as passable headroom whatever the gate's current state, so a
 * build gives the same graph whether the gate is open or closed; the spans under a door are tagged
 * with the door id and every edge through them records it in {@code gateDoorIds} (routing decides
 * availability, DESIGN §6.7).
 */
@FunctionalInterface
public interface GateCells {

    /** No gate cells at all. */
    GateCells NONE = (x, y, z) -> OptionalInt.empty();

    /** The id of the gate door whose closed footprint contains this block, if any. */
    OptionalInt doorAt(int x, int y, int z);
}
