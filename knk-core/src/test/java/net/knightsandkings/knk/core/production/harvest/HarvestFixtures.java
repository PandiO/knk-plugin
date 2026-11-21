package net.knightsandkings.knk.core.production.harvest;

import java.time.Instant;
import java.util.List;

import net.knightsandkings.knk.core.production.survey.AreaShape;
import net.knightsandkings.knk.core.production.survey.AreaSpec;

/** Shared rules and areas of the harvest tests (PandiO/knk-workspace#134). */
final class HarvestFixtures {

    static final int QUARRY = 3;
    static final int FARM = 1;
    static final int LOGGER = 2;

    private HarvestFixtures() {
    }

    /** Quarry rule: stone with a pickaxe of stone or better, node knk.harvest.quarry, bedrock placeholder, 60 s. */
    static HarvestRule stone() {
        return new HarvestRule(7, "Quarry stone", QUARRY, HarvestRule.TargetKind.BLOCK, List.of("stone", "*_ORE"), 0,
                "knk.harvest.quarry", null, HarvestRule.ToolClass.PICKAXE, HarvestRule.ToolTier.STONE, 0, 41,
                HarvestRule.Placeholder.DEPLETED_BLOCK, "BEDROCK", 60);
    }

    static HarvestRule wheat() {
        return new HarvestRule(8, "Wheat", FARM, HarvestRule.TargetKind.BLOCK, List.of("WHEAT"), 7, null, null,
                HarvestRule.ToolClass.ANY, HarvestRule.ToolTier.ANY, 10, 42, HarvestRule.Placeholder.REPLANT, null, 25);
    }

    static HarvestRule cows() {
        return new HarvestRule(9, "Cows", FARM, HarvestRule.TargetKind.ENTITY, List.of("COW"), 0, null, null,
                HarvestRule.ToolClass.ANY, HarvestRule.ToolTier.ANY, 0, 43, HarvestRule.Placeholder.NONE, null, 0);
    }

    /** A cuboid area 0..9 × 60..69 × 0..9 in "world". */
    static HarvestArea cuboid(int areaId, int structureId, int kindId, boolean productionEnabled, double fill) {
        AreaSpec spec = new AreaSpec(areaId, structureId, "Pit", "Pit", "Cuboid", true, true, "world", null, List.of(),
                null, null, null, null, 0, 60, 0, 9, 69, 9);
        return new HarvestArea(spec, kindId, productionEnabled, fill);
    }

    static HarvestCatalog catalog(List<HarvestRule> rules, HarvestArea... areas) {
        return new HarvestCatalog(true, Instant.EPOCH, rules, List.of(areas));
    }

    static HarvestAreaIndex index(HarvestCatalog catalog) {
        return HarvestAreaIndex.build(catalog.areas(), area -> area.spec().resolve(AreaSpec.RegionShapes.NONE, -64, 320),
                256);
    }

    static AreaShape shape(HarvestArea area) {
        return area.spec().resolve(AreaSpec.RegionShapes.NONE, -64, 320);
    }
}
