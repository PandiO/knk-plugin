package net.knightsandkings.knk.core.production.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * PandiO/knk-workspace#134 (P-D8): a harvested log fells its whole tree (logs and natural leaves inside the area), to be
 * restored by snapshot with a sapling at its lowest log. Too many logs is not a tree; a lone placed log is not one either;
 * leaves nearer another tree's logs stay.
 */
class TreeFellerTest {

    private final FakeRegenWorld world = new FakeRegenWorld();

    private void tree(int x, int z, int height, String log) {
        for (int y = 64; y < 64 + height; y++) {
            world.put(x, y, z, "minecraft:" + log + "[axis=y]");
        }
        int top = 64 + height;
        world.put(x, top, z, "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]");
        world.put(x + 1, top - 1, z, "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]");
        world.put(x + 2, top - 1, z, "minecraft:oak_leaves[distance=2,persistent=false,waterlogged=false]");
        world.put(x - 1, top - 1, z, "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]"); // placed
    }

    @Test
    void aLogFellsItsWholeTree() {
        tree(0, 0, 4, "oak_log");
        TreeFeller.Tree tree = TreeFeller.find(world::at, (x, y, z) -> true, 0, 66, 0, 256, 100);
        assertNotNull(tree);
        assertEquals(4, tree.logs().size());
        assertEquals(3, tree.leaves().size()); // the persistent (placed) leaves stay
        assertEquals(64, tree.base().y());
        assertEquals("OAK", tree.species());
        assertEquals("OAK_SAPLING", TreeFeller.saplingOf(tree.species()));
        assertEquals(7, TreeFeller.all(tree).size());
    }

    @Test
    void onlyTheAreasPartIsFelled() {
        tree(0, 0, 4, "oak_log");
        TreeFeller.Tree tree = TreeFeller.find(world::at, (x, y, z) -> y < 66 && x <= 1, 0, 64, 0, 256, 100);
        assertEquals(2, tree.logs().size());
        assertTrue(tree.leaves().isEmpty());
    }

    @Test
    void tooManyLogsIsNotATreeAndALonePlacedLogNeither() {
        tree(0, 0, 10, "spruce_log");
        assertNull(TreeFeller.find(world::at, (x, y, z) -> true, 0, 64, 0, 5, 100));
        world.put(20, 64, 20, "minecraft:oak_log[axis=x]");
        assertNull(TreeFeller.find(world::at, (x, y, z) -> true, 20, 64, 20, 256, 100));
        assertNull(TreeFeller.find(world::at, (x, y, z) -> true, 30, 64, 30, 256, 100)); // no log there
    }

    @Test
    void leavesNearerAnotherTreeStay() {
        tree(0, 0, 4, "oak_log");
        // a second tree two blocks east: the leaf at x=2 is distance 1 from its log, two steps from ours
        world.put(3, 67, 0, "minecraft:oak_log[axis=y]");
        world.put(2, 67, 0, "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]");
        world.put(3, 68, 0, "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]");
        TreeFeller.Tree tree = TreeFeller.find(world::at, (x, y, z) -> true, 0, 64, 0, 256, 100);
        assertTrue(tree.logs().stream().noneMatch(p -> p.x() == 3), "the other tree's log is not 26-connected");
        assertTrue(tree.leaves().stream().noneMatch(p -> p.x() == 2 && p.y() == 67));
    }

    @Test
    void saplingsPerSpecies() {
        assertEquals("MANGROVE_PROPAGULE", TreeFeller.saplingOf("MANGROVE"));
        assertEquals("CRIMSON_FUNGUS", TreeFeller.saplingOf("CRIMSON"));
        assertEquals("DARK_OAK_SAPLING", TreeFeller.saplingOf("DARK_OAK"));
        assertEquals("DARK_OAK", TreeFeller.speciesOf("DARK_OAK_WOOD"));
        assertTrue(TreeFeller.isLog("CHERRY_LOG"));
        assertTrue(!TreeFeller.isLog("STRIPPED_OAK_LOG"));
    }
}
