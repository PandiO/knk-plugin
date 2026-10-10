package net.knightsandkings.knk.core.roads.build;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PassabilityRulesTest {
    private final PassabilityRules rules = PassabilityRules.defaults();

    @Test
    void floorsAreSolidAndNotPassable() {
        for (String floor : List.of("STONE_BRICKS", "GRAVEL", "DIRT_PATH", "POLISHED_ANDESITE", "COBBLESTONE",
            "STONE_BRICK_SLAB", "STONE_BRICK_STAIRS", "OAK_PLANKS", "GRASS_BLOCK", "IRON_BARS", "OAK_FENCE",
            "BRAIN_CORAL_BLOCK", "STONE_BUTTON_PLATE_LIKE_UNKNOWN")) {
            assertTrue(rules.isSolid(floor), floor + " should be solid");
            assertFalse(rules.isPassable(floor), floor + " should not be passable");
            assertFalse(rules.isOverlay(floor), floor + " is not an overlay");
        }
    }

    @Test
    void airPlantsAndRedstonePartsArePassableAndNotSolid() {
        for (String passable : List.of("AIR", "CAVE_AIR", "SHORT_GRASS", "TALL_GRASS", "FERN", "OAK_SAPLING",
            "RED_TULIP", "POPPY", "TORCH", "WALL_TORCH", "REDSTONE_WIRE", "STONE_BUTTON", "OAK_SIGN",
            "OAK_WALL_SIGN", "RED_BANNER", "BRAIN_CORAL", "BRAIN_CORAL_FAN", "COBWEB", "WATER", "VINE")) {
            assertTrue(rules.isPassable(passable), passable + " should be passable");
            assertFalse(rules.isSolid(passable), passable + " should not be solid");
        }
    }

    @Test
    void overlaysArePassableNeverSolidAndRecognisedAsOverlay() {
        for (String overlay : List.of("SNOW", "RED_CARPET", "MOSS_CARPET", "PALE_MOSS_CARPET", "STONE_PRESSURE_PLATE",
            "OAK_PRESSURE_PLATE", "RAIL", "POWERED_RAIL", "DETECTOR_RAIL", "ACTIVATOR_RAIL", "LEAF_LITTER",
            "PINK_PETALS")) {
            assertTrue(rules.isOverlay(overlay), overlay + " should be an overlay");
            assertTrue(rules.isPassable(overlay), overlay + " should be passable");
            assertFalse(rules.isSolid(overlay), overlay + " should not be solid");
        }
        assertFalse(rules.isOverlay("SNOW_BLOCK"), "a snow block is a floor, not a layer");
        assertFalse(rules.isOverlay("STONE_BRICKS"));
    }

    @Test
    void passableAndSolidAreExactComplements() {
        for (String any : List.of("AIR", "STONE_BRICKS", "SNOW", "RED_CARPET", "OAK_STAIRS", "WATER", "TORCH",
            "MAGMA_BLOCK", "LAVA", "OAK_FENCE", "UNKNOWN_FUTURE_BLOCK")) {
            assertNotEquals(rules.isPassable(any), rules.isSolid(any), any);
        }
    }

    @Test
    void hazardSetIsTeleportsList() {
        Set<String> teleport = Set.of("LAVA", "MAGMA_BLOCK", "FIRE", "SOUL_FIRE", "CAMPFIRE", "SOUL_CAMPFIRE",
            "CACTUS", "SWEET_BERRY_BUSH", "POWDER_SNOW", "WITHER_ROSE", "POINTED_DRIPSTONE");
        assertEquals(teleport, PassabilityRules.HAZARD_MATERIALS);
        for (String hazard : teleport) {
            assertTrue(rules.isHazard(hazard), hazard);
        }
        assertFalse(rules.isHazard("STONE_BRICKS"));
        assertFalse(rules.isHazard("WATER"));
    }

    @Test
    void hazardsKeepTheirCollisionClass() {
        assertTrue(rules.isSolid("MAGMA_BLOCK"), "a magma block is a floor you should not stand on");
        assertTrue(rules.isPassable("LAVA"));
        assertTrue(rules.isPassable("FIRE"));
        assertTrue(rules.isSolid("CACTUS"));
    }

    @Test
    void stairsAndSlabsByName() {
        assertTrue(PassabilityRules.isStairOrSlab("STONE_BRICK_STAIRS"));
        assertTrue(PassabilityRules.isStairOrSlab("OAK_SLAB"));
        assertFalse(PassabilityRules.isStairOrSlab("STONE_BRICKS"));
        assertFalse(PassabilityRules.isStairOrSlab("SMOOTH_STONE"));
    }

    @Test
    void realCollisionTestReplacesTheCuratedTable() {
        // The paper side hands in Material#isCollidable(); here "everything but AIR collides".
        Predicate<String> collidable = name -> !name.equals("AIR");
        PassabilityRules real = PassabilityRules.of(collidable);

        assertTrue(real.isPassable("AIR"));
        assertFalse(real.isPassable("SHORT_GRASS"), "the curated list is not consulted any more");
        assertTrue(real.isPassable("RED_CARPET"), "overlays stay passable whatever the collision test says");
        assertFalse(real.isSolid("RED_CARPET"));
    }

    @Test
    void configuredOverlayPatternsSupportStarSuffixesAndCase() {
        PassabilityRules custom = PassabilityRules.of(PassabilityRules::curatedCollidable,
            List.of("snow", " *_carpet ", "RAIL"));

        assertTrue(custom.isOverlay("SNOW"));
        assertTrue(custom.isOverlay("BLUE_CARPET"));
        assertTrue(custom.isOverlay("RAIL"));
        assertFalse(custom.isOverlay("POWERED_RAIL"), "only the listed patterns");
        assertFalse(custom.isOverlay("STONE_PRESSURE_PLATE"));
    }

    @Test
    void patternMatchingIsExactUnlessStarred() {
        assertTrue(PassabilityRules.matchesPattern("SNOW", "SNOW"));
        assertFalse(PassabilityRules.matchesPattern("SNOW_BLOCK", "SNOW"));
        assertTrue(PassabilityRules.matchesPattern("RED_CARPET", "*_CARPET"));
        assertFalse(PassabilityRules.matchesPattern("CARPET", "*_CARPET"));
    }

    @Test
    void defaultOverlayPatternsMatchTheDesignConfig() {
        for (String fromDesign : List.of("SNOW", "*_CARPET", "*_PRESSURE_PLATE", "RAIL", "POWERED_RAIL",
            "LEAF_LITTER", "PINK_PETALS")) {
            assertTrue(PassabilityRules.DEFAULT_OVERLAY_PATTERNS.contains(fromDesign), fromDesign);
        }
    }

    // ===== walk search additions (KNG-51 §4) =====

    @Test
    void fencesWallsPanesBarsAndIronDoorsAreNeverAFloor() {
        for (String never : List.of("OAK_FENCE", "NETHER_BRICK_FENCE", "COBBLESTONE_WALL", "STONE_BRICK_WALL",
            "OAK_FENCE_GATE", "GLASS_PANE", "RED_STAINED_GLASS_PANE", "IRON_BARS", "IRON_DOOR", "IRON_TRAPDOOR")) {
            assertTrue(PassabilityRules.isNeverFloor(never), never);
            assertFalse(PassabilityRules.isWalkFloor(never), never);
        }
        for (String floor : List.of("STONE", "GRASS_BLOCK", "OAK_PLANKS", "STONE_BRICK_SLAB", "OAK_STAIRS",
            "OAK_TRAPDOOR", "WALL_TORCH", "REDSTONE_WALL_TORCH")) {
            assertFalse(PassabilityRules.isNeverFloor(floor), floor);
        }
        assertTrue(PassabilityRules.isWalkFloor("STONE"));
    }

    @Test
    void handOpenableDoorsAreWoodenAndCopperDoorsAndFenceGatesButNotIronOrTrapdoors() {
        for (String door : List.of("OAK_DOOR", "SPRUCE_DOOR", "CRIMSON_DOOR", "COPPER_DOOR", "WAXED_COPPER_DOOR",
            "OAK_FENCE_GATE", "BAMBOO_FENCE_GATE")) {
            assertTrue(PassabilityRules.isHandOpenableDoor(door), door);
            assertFalse(PassabilityRules.isWalkFloor(door), door + " is walked through, not on");
        }
        for (String not : List.of("IRON_DOOR", "OAK_TRAPDOOR", "IRON_TRAPDOOR", "OAK_FENCE", "STONE")) {
            assertFalse(PassabilityRules.isHandOpenableDoor(not), not);
        }
    }

    @Test
    void waterIsRecognisedByName() {
        assertTrue(PassabilityRules.isWater("WATER"));
        assertTrue(PassabilityRules.isWater("BUBBLE_COLUMN"));
        assertFalse(PassabilityRules.isWater("LAVA"));
        assertFalse(PassabilityRules.isWater("ICE"));
    }

    @Test
    void theWalkAdditionsLeaveTheBuildersPassabilityAlone() {
        // a fence is still solid and a door still collidable for the road builder: §4 adds walk rules, not new passability
        assertTrue(rules.isSolid("OAK_FENCE"));
        assertTrue(rules.isSolid("OAK_DOOR"));
        assertTrue(rules.isPassable("LADDER"));
    }
}
