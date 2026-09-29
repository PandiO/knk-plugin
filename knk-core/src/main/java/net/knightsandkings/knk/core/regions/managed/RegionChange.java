package net.knightsandkings.knk.core.regions.managed;

import java.util.Map;

/**
 * The difference between a region's actual and desired state. Null / empty parts are left alone, so applying a change can
 * only ever touch priority, parent and the flags the policy names.
 */
public record RegionChange(Integer newPriority, String newParentRegionId, Map<String, Object> flagsToSet) {

    public RegionChange {
        flagsToSet = flagsToSet != null ? Map.copyOf(flagsToSet) : Map.of();
    }

    public boolean isEmpty() {
        return newPriority == null && newParentRegionId == null && flagsToSet.isEmpty();
    }

    /** A short description for logs: {@code priority 0->20, parent town_1, flags [pvp, mob-spawning]}. */
    public String describe(ActualRegionState actual) {
        StringBuilder text = new StringBuilder();
        if (newPriority != null) {
            text.append("priority ").append(actual != null ? actual.priority() : "?").append("->").append(newPriority);
        }
        if (newParentRegionId != null) {
            text.append(text.length() > 0 ? ", " : "").append("parent ").append(newParentRegionId);
        }
        if (!flagsToSet.isEmpty()) {
            text.append(text.length() > 0 ? ", " : "").append("flags ").append(new java.util.TreeSet<>(flagsToSet.keySet()));
        }
        return text.toString();
    }
}
