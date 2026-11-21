package net.knightsandkings.knk.core.production.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** PandiO/knk-workspace#134: rule targets with {@code *} at either end, the API's enum names, block states and tools. */
class HarvestRuleTest {

    @Test
    void targetsMatchExactlyOrWithAWildcardAtEitherEnd() {
        HarvestRule rule = new HarvestRule(1, "r", 1, null, List.of("*_LOG", "STONE", "DEEPSLATE_*", "*COPPER*"), 0, " ",
                0, null, null, 0, 1, null, " ", 0);
        assertTrue(rule.targets("OAK_LOG"));
        assertTrue(rule.targets("stone"));
        assertTrue(rule.targets("DEEPSLATE_COAL_ORE"));
        assertTrue(rule.targets("RAW_COPPER_BLOCK"));
        assertFalse(rule.targets("STONE_BRICKS"));
        assertFalse(rule.targets("OAK_PLANKS"));
        assertFalse(rule.targets(null));
        assertNull(rule.requiredPermission());
        assertNull(rule.minTitleBracketId());
        assertNull(rule.placeholderMaterial());
        assertTrue(new HarvestRule(1, "r", 1, null, List.of("*"), 0, null, null, null, null, 0, 1, null, null, 0)
                .targets("ANYTHING"));
    }

    @Test
    void apiEnumNamesParse() {
        assertEquals(HarvestRule.Placeholder.DEPLETED_BLOCK,
                HarvestRule.parse(HarvestRule.Placeholder.class, "DepletedBlock", HarvestRule.Placeholder.NONE));
        assertEquals(HarvestRule.ToolClass.FISHING_ROD,
                HarvestRule.parse(HarvestRule.ToolClass.class, "FishingRod", HarvestRule.ToolClass.ANY));
        assertEquals(HarvestRule.TargetKind.ENTITY,
                HarvestRule.parse(HarvestRule.TargetKind.class, "Entity", HarvestRule.TargetKind.BLOCK));
        assertEquals(HarvestRule.ToolTier.ANY, HarvestRule.parse(HarvestRule.ToolTier.class, "Mithril", HarvestRule.ToolTier.ANY));
    }

    @Test
    void blockStatesReadMaterialAndAge() {
        assertEquals("WHEAT", BlockStates.material("minecraft:wheat[age=7]"));
        assertEquals(7, BlockStates.age("minecraft:wheat[age=7]"));
        assertEquals(-1, BlockStates.age("minecraft:stone"));
        assertEquals("minecraft:wheat[age=0]", BlockStates.withAgeZero("minecraft:wheat[age=7]"));
        assertEquals("minecraft:cocoa[age=0,facing=north]", BlockStates.withAgeZero("minecraft:cocoa[age=2,facing=north]"));
        assertEquals("minecraft:stone", BlockStates.withAgeZero("minecraft:stone"));
    }

    @Test
    void toolsHaveAClassAndATier() {
        HarvestRule rule = HarvestFixtures.stone(); // pickaxe of stone or better
        assertNull(HarvestTools.unmet(rule, "IRON_PICKAXE"));
        assertNull(HarvestTools.unmet(rule, "COPPER_PICKAXE"));
        assertEquals("needs a stone tool or better", HarvestTools.unmet(rule, "WOODEN_PICKAXE"));
        assertEquals("needs a pickaxe", HarvestTools.unmet(rule, "DIAMOND_SHOVEL"));
        assertEquals("needs a pickaxe", HarvestTools.unmet(rule, null));
        assertEquals(HarvestRule.ToolClass.AXE, HarvestTools.classOf("NETHERITE_AXE"));
        assertEquals(HarvestRule.ToolClass.SHEARS, HarvestTools.classOf("SHEARS"));
        HarvestRule shears = new HarvestRule(1, "r", 1, null, List.of("*_LEAVES"), 0, null, null, HarvestRule.ToolClass.SHEARS,
                null, 0, 1, null, null, 0);
        assertEquals("needs shears", HarvestTools.unmet(shears, "STICK"));
        assertNull(HarvestTools.unmet(HarvestFixtures.wheat(), null)); // any tool, even a hand
    }
}
