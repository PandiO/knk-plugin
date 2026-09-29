package net.knightsandkings.knk.core.regions.managed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns {@link ManagedRegionSpec}s into the desired state of each region: resolved parent, priority and flag rules.
 * Pure: it never reads WorldGuard, only the {@link ExternalRegions} lookup it is given.
 */
public final class ManagedRegionPlanner {

    /** Existing regions that are not part of the batch being planned (a new region's already-existing parent). */
    @FunctionalInterface
    public interface ExternalRegions {
        Optional<ActualRegionState> find(String regionId);
    }

    /**
     * @param parentRegionId    the resolved parent to set, or null when there is none or it could not be resolved (then the
     *                          region's current parent is left alone, never cleared)
     * @param requestedParentId what the data asked for, for logs
     */
    public record PlannedRegion(ManagedRegionSpec spec, ManagedRegionKind kind, String parentRegionId,
                                String requestedParentId, int priority, List<FlagRule> flagRules) {
        public String regionId() {
            return spec.regionId();
        }
    }

    /** {@code regions} lists parents before their children. */
    public record Plan(List<PlannedRegion> regions, List<String> warnings) {
    }

    private final ManagedRegionPolicy policy;
    private final Map<String, RegionOverride> overrides;

    public ManagedRegionPlanner(ManagedRegionPolicy policy, Map<String, RegionOverride> overrides) {
        this.policy = policy != null ? policy : new ManagedRegionPolicy();
        Map<String, RegionOverride> keyed = new HashMap<>();
        if (overrides != null) {
            overrides.forEach((id, override) -> keyed.put(ManagedRegionSpec.key(id), override));
        }
        this.overrides = keyed;
    }

    public Plan plan(Collection<ManagedRegionSpec> specs, ExternalRegions external) {
        List<String> warnings = new ArrayList<>();
        Map<String, ManagedRegionSpec> byKey = new LinkedHashMap<>();
        for (ManagedRegionSpec spec : specs) {
            byKey.putIfAbsent(spec.key(), spec);
        }

        // 1. Resolve every region's parent link: a planned region, an existing external one, or none.
        Map<String, String> plannedParent = new HashMap<>();
        Map<String, Integer> externalParentPriority = new HashMap<>();
        Map<String, String> requestedParent = new HashMap<>();
        Map<String, ManagedRegionKind> kinds = new HashMap<>();
        for (ManagedRegionSpec spec : byKey.values()) {
            RegionOverride override = overrides.get(spec.key());
            ManagedRegionKind kind = override != null && override.kind() != null ? override.kind() : spec.kind();
            String requested = override != null && override.parentRegionId() != null ? override.parentRegionId() : spec.parentRegionId();
            kinds.put(spec.key(), kind);
            String parentKey = ManagedRegionSpec.key(requested);
            requestedParent.put(spec.key(), requested);
            if (parentKey == null || parentKey.isEmpty()) {
                continue;
            }
            if (parentKey.equals(spec.key())) {
                warnings.add(spec.regionId() + ": lists itself as its parent; ignored");
            } else if (byKey.containsKey(parentKey)) {
                plannedParent.put(spec.key(), parentKey);
            } else {
                Optional<ActualRegionState> found = external != null ? external.find(requested) : Optional.empty();
                if (found.isPresent()) {
                    plannedParent.put(spec.key(), parentKey);
                    externalParentPriority.put(parentKey, found.get().priority());
                } else {
                    warnings.add(spec.regionId() + ": parent region '" + requested + "' does not exist; parent left as it is");
                }
            }
        }

        // 2. Break parent cycles (a bad override or stale data), so the priority walk terminates.
        for (String start : byKey.keySet()) {
            Set<String> path = new HashSet<>();
            String previous = null;
            String current = start;
            while (current != null && byKey.containsKey(current)) {
                if (!path.add(current)) {
                    warnings.add(byKey.get(previous).regionId() + ": parent chain loops back on itself; parent link dropped");
                    plannedParent.remove(previous);
                    break;
                }
                previous = current;
                current = plannedParent.get(current);
            }
        }

        // 3. Priorities, parents first.
        Map<String, Integer> priorities = new HashMap<>();
        List<PlannedRegion> planned = new ArrayList<>();
        Map<String, Integer> depth = new HashMap<>();
        for (String key : byKey.keySet()) {
            priorityOf(key, byKey, kinds, plannedParent, externalParentPriority, priorities, depth);
        }
        List<String> order = new ArrayList<>(byKey.keySet());
        order.sort((a, b) -> Integer.compare(depth.get(a), depth.get(b)));
        for (String key : order) {
            ManagedRegionSpec spec = byKey.get(key);
            RegionOverride override = overrides.get(key);
            String parentKey = plannedParent.get(key);
            String parentId = parentKey == null ? null
                    : byKey.containsKey(parentKey) ? byKey.get(parentKey).regionId() : requestedParent.get(key);
            planned.add(new PlannedRegion(spec, kinds.get(key), parentId, requestedParent.get(key), priorities.get(key),
                    rulesFor(kinds.get(key), override)));
        }
        return new Plan(planned, warnings);
    }

    private int priorityOf(String key, Map<String, ManagedRegionSpec> byKey, Map<String, ManagedRegionKind> kinds,
                           Map<String, String> plannedParent, Map<String, Integer> externalParentPriority,
                           Map<String, Integer> priorities, Map<String, Integer> depth) {
        Integer known = priorities.get(key);
        if (known != null) {
            return known;
        }
        String parentKey = plannedParent.get(key);
        Integer parentPriority = null;
        int level = 0;
        if (parentKey != null) {
            if (byKey.containsKey(parentKey)) {
                parentPriority = priorityOf(parentKey, byKey, kinds, plannedParent, externalParentPriority, priorities, depth);
                level = depth.get(parentKey) + 1;
            } else {
                parentPriority = externalParentPriority.get(parentKey);
                level = 1;
            }
        }
        RegionOverride override = overrides.get(key);
        int priority = override != null && override.priority() != null
                ? override.priority()
                : policy.priorityFor(kinds.get(key), parentPriority);
        priorities.put(key, priority);
        depth.put(key, level);
        return priority;
    }

    private List<FlagRule> rulesFor(ManagedRegionKind kind, RegionOverride override) {
        List<FlagRule> rules = new ArrayList<>(policy.flagRules(kind));
        if (override != null) {
            for (FlagRule extra : override.extraFlags()) {
                rules.removeIf(rule -> rule.flag().equalsIgnoreCase(extra.flag()));
                rules.add(extra);
            }
        }
        return rules;
    }
}
