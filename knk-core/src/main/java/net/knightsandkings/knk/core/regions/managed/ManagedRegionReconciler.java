package net.knightsandkings.knk.core.regions.managed;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Brings existing WorldGuard regions to the state {@link ManagedRegionPolicy} wants, and only that: priority, parent and
 * the policy's flags. It never creates or deletes a region and never touches owners, members or other flags.
 * <p>
 * This one class serves both paths: the startup repair passes every domain-backed region, region creation passes the one
 * region it just made. Running it twice on the same data changes nothing the second time.
 */
public final class ManagedRegionReconciler {

    private final ManagedRegionPlanner planner;

    public ManagedRegionReconciler(ManagedRegionPolicy policy, Map<String, RegionOverride> overrides) {
        this.planner = new ManagedRegionPlanner(policy, overrides);
    }

    /** Reconciles one region (the creation path); its parent, if it exists in the store, is picked up from there. */
    public RepairReport reconcileOne(ManagedRegionSpec spec, ManagedRegionStore store) {
        return reconcile(List.of(spec), store);
    }

    public RepairReport reconcile(Collection<ManagedRegionSpec> specs, ManagedRegionStore store) {
        Objects.requireNonNull(store, "store");
        RepairReport report = new RepairReport();

        // 1. One spec per region. Two domains claiming one region with different ideas is ambiguous: touch neither.
        Map<String, List<ManagedRegionSpec>> byKey = new LinkedHashMap<>();
        for (ManagedRegionSpec spec : specs) {
            byKey.computeIfAbsent(spec.key(), key -> new ArrayList<>()).add(spec);
        }
        List<ManagedRegionSpec> candidates = new ArrayList<>();
        for (List<ManagedRegionSpec> group : byKey.values()) {
            ManagedRegionSpec first = group.get(0);
            boolean conflicting = group.stream().anyMatch(other -> other.kind() != first.kind()
                    || !Objects.equals(ManagedRegionSpec.key(other.parentRegionId()), ManagedRegionSpec.key(first.parentRegionId())));
            if (conflicting) {
                report.add(first.regionId(), RepairReport.Outcome.SKIPPED,
                        "claimed by several domains with different kind/parent: " + sources(group));
            } else {
                candidates.add(first);
            }
        }

        // 2. Read every region once; a missing one (stale domain record) is skipped, never created.
        Map<String, ActualRegionState> actual = new HashMap<>();
        List<ManagedRegionSpec> present = new ArrayList<>();
        for (ManagedRegionSpec spec : candidates) {
            try {
                Optional<ActualRegionState> state = store.read(spec.regionId());
                if (state.isPresent()) {
                    actual.put(spec.key(), state.get());
                    present.add(spec);
                } else {
                    report.add(spec.regionId(), RepairReport.Outcome.SKIPPED,
                            "no such region in WorldGuard (stale " + spec.source() + ")");
                }
            } catch (RegionStoreException | RuntimeException e) {
                report.add(spec.regionId(), RepairReport.Outcome.FAILED, "could not read region: " + e.getMessage());
            }
        }

        // 3. Plan, then apply parents before children.
        ManagedRegionPlanner.Plan plan = planner.plan(present, regionId -> {
            try {
                return store.read(regionId);
            } catch (RegionStoreException | RuntimeException e) {
                return Optional.empty();
            }
        });
        plan.warnings().forEach(report::warn);

        Set<String> unsupportedWarned = new HashSet<>();
        boolean anyChanged = false;
        for (ManagedRegionPlanner.PlannedRegion planned : plan.regions()) {
            ActualRegionState state = actual.get(planned.spec().key());
            RegionChange change = diff(planned, state, store, report, unsupportedWarned);
            if (change.isEmpty()) {
                report.add(planned.regionId(), RepairReport.Outcome.UNCHANGED, planned.kind().name());
                continue;
            }
            try {
                store.apply(planned.regionId(), change);
                anyChanged = true;
                report.add(planned.regionId(), RepairReport.Outcome.CHANGED,
                        planned.kind() + ": " + change.describe(state));
            } catch (RegionStoreException | RuntimeException e) {
                report.add(planned.regionId(), RepairReport.Outcome.FAILED, "could not apply (nothing changed): " + e.getMessage());
            }
        }

        // 4. Save once, after all the in-memory changes.
        if (anyChanged) {
            try {
                store.persist();
            } catch (RegionStoreException | RuntimeException e) {
                report.persistFailed(e.getMessage());
            }
        }
        return report;
    }

    private static RegionChange diff(ManagedRegionPlanner.PlannedRegion planned, ActualRegionState actual,
                                     ManagedRegionStore store, RepairReport report, Set<String> unsupportedWarned) {
        Integer newPriority = planned.priority() != actual.priority() ? planned.priority() : null;
        String newParent = planned.parentRegionId() != null
                && !ManagedRegionSpec.key(planned.parentRegionId()).equals(ManagedRegionSpec.key(actual.parentRegionId()))
                ? planned.parentRegionId() : null;

        Map<String, Object> flags = new LinkedHashMap<>();
        for (FlagRule rule : planned.flagRules()) {
            if (!store.supportsFlag(rule.flag())) {
                if (unsupportedWarned.add(rule.flag())) {
                    report.warn("flag '" + rule.flag() + "' is not available in this WorldGuard; not applied (" + planned.kind() + ")");
                }
                continue;
            }
            Object current = actual.flags().get(rule.flag());
            boolean needsSet = switch (rule.mode()) {
                case SEED_IF_ABSENT -> current == null;
                case ENFORCE -> !rule.value().equals(current);
            };
            if (needsSet) {
                flags.put(rule.flag(), rule.value());
            }
        }
        return new RegionChange(newPriority, newParent, flags);
    }

    private static String sources(List<ManagedRegionSpec> group) {
        return group.stream().map(ManagedRegionSpec::source).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
