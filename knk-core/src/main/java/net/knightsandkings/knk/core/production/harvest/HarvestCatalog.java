package net.knightsandkings.knk.core.production.harvest;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET api/Harvest/catalog} (PandiO/knk-workspace#685): the enabled rules and the areas they apply in. With
 * {@code featureEnabled} false (the API's {@code Production:Enabled} off) both lists are empty and no break is allowed.
 */
public record HarvestCatalog(boolean featureEnabled, Instant generatedAt, List<HarvestRule> rules, List<HarvestArea> areas) {

    public static final HarvestCatalog EMPTY = new HarvestCatalog(false, null, List.of(), List.of());

    public HarvestCatalog {
        rules = rules == null ? List.of() : List.copyOf(rules);
        areas = areas == null ? List.of() : List.copyOf(areas);
    }

    /** The rules of {@code kindId} for this target kind that list {@code name}, in the catalogue's order. */
    public List<HarvestRule> rulesFor(int kindId, HarvestRule.TargetKind kind, String name) {
        return rules.stream()
                .filter(r -> r.productionKindId() == kindId && r.targetKind() == kind && r.targets(name))
                .toList();
    }

    /** Whether any rule of this target kind lists {@code name} (a quick filter before the area lookup). */
    public boolean anyRuleTargets(HarvestRule.TargetKind kind, String name) {
        for (HarvestRule rule : rules) {
            if (rule.targetKind() == kind && rule.targets(name)) {
                return true;
            }
        }
        return false;
    }
}
