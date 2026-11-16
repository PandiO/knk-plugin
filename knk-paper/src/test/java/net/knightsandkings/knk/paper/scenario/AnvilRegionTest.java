package net.knightsandkings.knk.paper.scenario;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.snapshot.NavSnapshotZip;
import net.knightsandkings.knk.core.npc.nav.snapshot.SnapshotBox;

/**
 * The Anvil reader of the snapshot replay (link B1, PandiO/knk-workspace#670): the sample snapshot's region files hold
 * exactly the developer's stair house schematic at its real place, read back block for block.
 */
class AnvilRegionTest {

    static NavSnapshotZip.Loaded sample() throws Exception {
        try (InputStream in = AnvilRegionTest.class.getResourceAsStream("/scenario-snapshots/stairhouse-sample.zip")) {
            return NavSnapshotZip.read(in);
        }
    }

    /** A block state with its properties in name order (WorldEdit keeps them in its own order). */
    static String sorted(String state) {
        int open = state.indexOf('[');
        if (open < 0) {
            return state;
        }
        TreeSet<String> props = new TreeSet<>(Arrays.asList(state.substring(open + 1, state.length() - 1).split(",")));
        return state.substring(0, open) + "[" + String.join(",", props) + "]";
    }

    @Test
    void theSampleRegionFilesHoldTheStairHouseBlockForBlock() throws Exception {
        NavSnapshotZip.Loaded loaded = sample();
        Map<String, String> read = new HashMap<>();
        SnapshotBox everything = new SnapshotBox(new int[] {1300, -64, -700}, new int[] {1600, 319, -400});
        int chunks = 0;
        for (Map.Entry<String, byte[]> file : loaded.files().entrySet()) {
            AnvilRegion.Read r = AnvilRegion.blocks(file.getKey(), file.getValue(), everything,
                (x, y, z, state) -> read.put(x + " " + y + " " + z, state));
            assertEquals(0, r.skipped(), r.problems().toString());
            chunks += r.chunks();
        }
        assertTrue(chunks > 0);

        SceneSchematic schematic = SceneSchematic.fromResource(NpcMovementScene.STAIRHOUSE);
        int[] min = schematic.worldMin();
        int checked = 0;
        for (int x = 0; x < schematic.width(); x++) {
            for (int y = 0; y < schematic.height(); y++) {
                for (int z = 0; z < schematic.length(); z++) {
                    String expected = schematic.blockAt(x, y, z);
                    if (expected == null || expected.equals("minecraft:air") || expected.equals("minecraft:cave_air")) {
                        continue;
                    }
                    String key = (min[0] + x) + " " + (min[1] + y) + " " + (min[2] + z);
                    assertEquals(sorted(expected), read.get(key), key);
                    checked++;
                }
            }
        }
        assertTrue(checked > 10_000, "the stair house has " + checked + " blocks");
        assertEquals("minecraft:stone", read.get("1407 46 -549"), "the floor under the house");
        assertEquals("minecraft:stone", read.get("1431 48 -530"), "the street east of it");
        assertEquals(null, read.get("1432 48 -530"));
    }

    @Test
    void theDevelopersRegionFilesOfMinecraft26AreRead() throws Exception {
        // the dev server's chunks keep palette entries as block state strings, some wrapped in {"": ...} (2026-11-16)
        NavSnapshotZip.Loaded loaded;
        try (InputStream in = getClass().getResourceAsStream("/scenario-snapshots/quarry-to-keep-stairhouse.zip")) {
            loaded = NavSnapshotZip.read(in);
        }
        var m = loaded.snapshot().manifest();
        SnapshotBox box = new SnapshotBox(m.boxMin(), m.boxMax());
        Map<String, String> read = new HashMap<>();
        int skipped = 0;
        for (Map.Entry<String, byte[]> file : loaded.files().entrySet()) {
            AnvilRegion.Read r = AnvilRegion.blocks(file.getKey(), file.getValue(), box,
                (x, y, z, state) -> read.put(x + " " + y + " " + z, state));
            skipped += r.skipped();
        }
        assertEquals(0, skipped);
        assertTrue(read.size() > 5_000, read.size() + " blocks");
        // the stair house in the dev world is the developer's schematic: a flight's stair block is there
        SceneSchematic schematic = SceneSchematic.fromResource(NpcMovementScene.STAIRHOUSE);
        int[] min = schematic.worldMin();
        int same = 0;
        int solid = 0;
        for (int x = 0; x < schematic.width(); x++) {
            for (int y = 0; y < schematic.height(); y++) {
                for (int z = 0; z < schematic.length(); z++) {
                    String expected = schematic.blockAt(x, y, z);
                    if (expected == null || expected.endsWith(":air")) {
                        continue;
                    }
                    solid++;
                    String actual = read.get((min[0] + x) + " " + (min[1] + y) + " " + (min[2] + z));
                    if (actual != null && sorted(actual).equals(sorted(expected))) {
                        same++;
                    }
                }
            }
        }
        // 85 % on 2026-11-16: the dev world changed a little after the schematic was copied (2026-11-15)
        assertTrue(same > solid * 8 / 10, same + " of " + solid + " schematic blocks are the same in the snapshot");
    }

    @Test
    void paletteEntriesInEveryForm() {
        assertEquals("minecraft:stone", AnvilRegion.paletteEntry("minecraft:stone"));
        assertEquals("minecraft:tuff", AnvilRegion.paletteEntry(Map.of("", "minecraft:tuff")));
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", AnvilRegion.paletteEntry(Map.of("Name",
            "minecraft:oak_stairs", "Properties", Map.of("half", "bottom", "facing", "north"))));
    }

    @Test
    void onlyTheBoxIsRead() throws Exception {
        NavSnapshotZip.Loaded loaded = sample();
        SnapshotBox one = new SnapshotBox(new int[] {1407, 46, -549}, new int[] {1407, 46, -549});
        int[] n = {0};
        for (Map.Entry<String, byte[]> file : loaded.files().entrySet()) {
            AnvilRegion.blocks(file.getKey(), file.getValue(), one, (x, y, z, state) -> n[0]++);
        }
        assertEquals(1, n[0]);
    }

    @Test
    void regionNamesAndBrokenFiles() {
        assertArrayEquals(new int[] {2, -2}, AnvilRegion.regionOf("world/region/r.2.-2.mca"));
        assertThrows(IllegalArgumentException.class, () -> AnvilRegion.regionOf("level.dat"));
        AnvilRegion.Read r = AnvilRegion.blocks("r.0.0.mca", new byte[100],
            new SnapshotBox(new int[] {0, 0, 0}, new int[] {1, 1, 1}), (x, y, z, s) -> { });
        assertEquals(1, r.problems().size());
        // a header pointing past the end of the file: that chunk is skipped with a problem, the rest goes on
        byte[] bad = new byte[8192];
        bad[2] = 9; // chunk 0 0 at sector 9, one sector long: beyond the file's two header sectors
        bad[3] = 1;
        AnvilRegion.Read r2 = AnvilRegion.blocks("r.0.0.mca", bad,
            new SnapshotBox(new int[] {0, 0, 0}, new int[] {15, 15, 15}), (x, y, z, s) -> { });
        assertEquals(1, r2.skipped());
    }
}
