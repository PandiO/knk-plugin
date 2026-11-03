package net.knightsandkings.knk.core.survival.spawn;

import net.knightsandkings.knk.core.survival.SurvivalPoint;

import java.util.Set;

/**
 * An NPC spawnzone (DESIGN §6, S10, S22): active from the start when it has no prerequisite, otherwise once the
 * listed objectives are NPC-held per the policy. Activation is ownership-driven only: an open or destroyed door
 * is not an input here, so a breach alone never activates a zone.
 *
 * @param id            the zone's id
 * @param weight        spawn weight among the active zones (> 0)
 * @param prerequisites objective ids that must be NPC-held; empty = initially active
 * @param policy        ALL or ANY over the prerequisites
 * @param anchor        a representative position for the distance to the players (the zone's centre)
 */
public record SpawnZone(String id, double weight, Set<String> prerequisites, PrerequisitePolicy policy, SurvivalPoint anchor) {

    public SpawnZone {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (!Double.isFinite(weight) || weight <= 0) throw new IllegalArgumentException("weight must be > 0: " + weight);
        prerequisites = Set.copyOf(prerequisites);
        if (policy == null) policy = PrerequisitePolicy.ALL;
        if (anchor == null) throw new IllegalArgumentException("anchor is required");
    }

    public static SpawnZone initial(String id, double weight, SurvivalPoint anchor) {
        return new SpawnZone(id, weight, Set.of(), PrerequisitePolicy.ALL, anchor);
    }

    public static SpawnZone unlockedBy(String id, double weight, SurvivalPoint anchor, PrerequisitePolicy policy, String... objectiveIds) {
        return new SpawnZone(id, weight, Set.of(objectiveIds), policy, anchor);
    }

    public boolean isInitial() {
        return prerequisites.isEmpty();
    }

    /** Whether the zone is eligible given the objectives the NPC team holds right now. */
    public boolean isEligible(Set<String> npcHeldObjectives) {
        if (prerequisites.isEmpty()) return true;
        return switch (policy) {
            case ALL -> npcHeldObjectives.containsAll(prerequisites);
            case ANY -> prerequisites.stream().anyMatch(npcHeldObjectives::contains);
        };
    }
}
