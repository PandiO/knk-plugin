package net.knightsandkings.knk.paper.scenario;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.bukkit.Material;
import org.bukkit.block.data.type.Stairs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * The scenario scene loader (implementation plan 4c.4 item 1.3, link M1, PandiO/knk-workspace#621): WorldEdit's
 * Sponge schematics read without WorldEdit, the copy origin and real corner WorldEdit writes, and the paste into a
 * scenario box on a MockBukkit world, including the shipped stand-in scenes.
 */
class SceneSchematicTest {

    private ServerMock server;
    // kept in a field, never inline (knk-plugin CLAUDE.md: a Location holds its world weakly)
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("scenes");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private ScenarioScene scene() {
        return new ScenarioScene("scene-test", 1, world, 64, 70, 0,
            new ScenarioServices(null, () -> null, () -> null, () -> null, () -> null, () -> null, () -> null, () -> null,
                () -> null, () -> null, () -> null, () -> null, () -> null));
    }

    // ==================== reading ====================

    @Test
    void aVersionThreeSchematicIsReadWithItsOriginAndRealCorner() throws IOException {
        // 2 x 1 x 2 as WorldEdit 7.3+ writes it: Offset is the corner minus the origin, the origin's real position in
        // Metadata.WorldEdit.Origin
        byte[] file = v3(2, 1, 2, List.of("minecraft:air", "minecraft:stone", "minecraft:oak_planks"),
            new int[] {1, 0, 2, 1}, new int[] {-1, 0, -1}, new int[] {1413, 49, -508});

        SceneSchematic s = SceneSchematic.read(new ByteArrayInputStream(file));

        assertEquals(2, s.width());
        assertEquals(1, s.height());
        assertEquals(2, s.length());
        assertEquals("minecraft:stone", s.blockAt(0, 0, 0));
        assertEquals("minecraft:air", s.blockAt(1, 0, 0));
        assertEquals("minecraft:oak_planks", s.blockAt(0, 0, 1), "x first, then z, then y");
        assertArrayEquals(new int[] {1, 0, 1}, s.origin(), "where the copying player stood");
        assertArrayEquals(new int[] {1412, 49, -509}, s.worldMin());
        assertArrayEquals(new int[] {1, 0, 1}, s.local(1413, 49, -508));
        assertEquals(5023, s.dataVersion());
    }

    @Test
    void aFileSavedByWorldEditItselfIsRead() throws IOException {
        // saved on the paper-boot server (WorldEdit 7.4.6, Paper 26.3) from the console: //world world,
        // //pos1 0,-61,0, //pos2 2,-60,1, //copy, //schem save m1check (2026-11-15, PandiO/knk-workspace#621)
        try (var in = getClass().getClassLoader().getResourceAsStream("scenario-scenes-test/worldedit-7.4.6-console.schem")) {
            SceneSchematic s = SceneSchematic.read(in);

            assertEquals(3, s.width());
            assertEquals(2, s.height());
            assertEquals(2, s.length());
            assertEquals(5023, s.dataVersion(), "Minecraft 26.3");
            assertArrayEquals(new int[] {0, -61, 0}, s.worldMin(), "//pos1, the region's lowest corner");
            assertArrayEquals(new int[] {0, 0, 0}, s.origin(), "the console copies from the region's corner");
            assertEquals("minecraft:stone", s.blockAt(0, 0, 0));
            assertEquals("minecraft:grass_block[snowy=false]", s.blockAt(2, 1, 1), "block states come along");
            assertEquals(new SceneSchematic.Pasted(12, 0), s.paste(scene(), 0, 0, 0));
        }
    }

    @Test
    void aVersionTwoSchematicIsReadToo() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(10);
        out.writeUTF("Schematic");
        intTag(out, "Version", 2);
        intTag(out, "DataVersion", 3700);
        shortTag(out, "Width", 1);
        shortTag(out, "Height", 2);
        shortTag(out, "Length", 1);
        intArray(out, "Offset", 100, 64, 200);
        out.writeByte(10);
        out.writeUTF("Metadata");
        intTag(out, "WEOffsetX", -1);
        intTag(out, "WEOffsetY", 0);
        intTag(out, "WEOffsetZ", 0);
        out.writeByte(0);
        palette(out, List.of("minecraft:air", "minecraft:stone"));
        byteArray(out, "BlockData", new byte[] {1, 0});
        out.writeByte(0);

        SceneSchematic s = SceneSchematic.read(new ByteArrayInputStream(gzip(raw.toByteArray())));

        assertEquals("minecraft:stone", s.blockAt(0, 0, 0));
        assertEquals("minecraft:air", s.blockAt(0, 1, 0), "y is the slowest index");
        assertArrayEquals(new int[] {100, 64, 200}, s.worldMin());
        assertArrayEquals(new int[] {1, 0, 0}, s.origin());
    }

    @Test
    void paletteIndexesOfMoreThanOneByteAreVarints() throws IOException {
        List<String> palette = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            palette.add(i == 150 ? "minecraft:gold_block" : "minecraft:stone");
        }
        palette.set(0, "minecraft:air");
        // index 150 is 0x96 0x01 as a varint
        byte[] file = v3Raw(1, 1, 2, palette, new byte[] {(byte) 0x96, 0x01, 0x00}, new int[] {0, 0, 0}, null);

        SceneSchematic s = SceneSchematic.read(new ByteArrayInputStream(file));

        assertEquals("minecraft:gold_block", s.blockAt(0, 0, 0));
        assertEquals("minecraft:air", s.blockAt(0, 0, 1));
    }

    @Test
    void aBrokenFileIsRefusedWithTheReason() throws IOException {
        byte[] shortData = v3Raw(2, 1, 2, List.of("minecraft:air", "minecraft:stone"), new byte[] {1, 1},
            new int[] {0, 0, 0}, null);
        IOException e = assertThrows(IOException.class, () -> SceneSchematic.read(new ByteArrayInputStream(shortData)));
        assertTrue(e.getMessage().contains("block data ends after 2 of 4 blocks"), e.getMessage());

        byte[] badIndex = v3Raw(1, 1, 1, List.of("minecraft:air"), new byte[] {3}, new int[] {0, 0, 0}, null);
        assertThrows(IllegalArgumentException.class, () -> SceneSchematic.read(new ByteArrayInputStream(badIndex)));
        assertThrows(IOException.class, () -> SceneSchematic.read(new ByteArrayInputStream(gzip(new byte[] {8, 0, 0}))));
    }

    // ==================== pasting ====================

    @Test
    void aPasteSetsTheBlocksIntoTheBoxAndSkipsAirAndWhatFallsOutside() throws IOException {
        byte[] file = v3(2, 1, 2, List.of("minecraft:air", "minecraft:stone",
                "minecraft:stone_brick_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]"),
            new int[] {1, 0, 2, 1}, new int[] {0, 0, 0}, null);
        SceneSchematic s = SceneSchematic.read(new ByteArrayInputStream(file));
        ScenarioScene scene = scene();

        SceneSchematic.Pasted pasted = s.paste(scene, 4, 2, 6);

        assertEquals(new SceneSchematic.Pasted(3, 0), pasted);
        assertEquals(Material.STONE, scene.block(4, 2, 6).getType());
        assertEquals(Material.AIR, scene.block(5, 2, 6).getType());
        assertEquals(Material.STONE_BRICK_STAIRS, scene.block(4, 2, 7).getType());
        assertEquals(org.bukkit.block.BlockFace.SOUTH, ((Stairs) scene.block(4, 2, 7).getBlockData()).getFacing(),
            "the block state's properties come along");
        assertEquals(Material.STONE, scene.block(5, 2, 7).getType());

        SceneSchematic.Pasted edge = s.paste(scene, ScenarioScene.SIZE - 1, 0, 0);
        assertEquals(new SceneSchematic.Pasted(2, 1), edge, "the block at x 32 is left out and counted");
    }

    @Test
    void aBlockTheServerDoesNotKnowFailsThePasteWithItsName() throws IOException {
        byte[] file = v3(1, 1, 1, List.of("minecraft:air", "minecraft:no_such_block"), new int[] {1},
            new int[] {0, 0, 0}, null);
        SceneSchematic s = SceneSchematic.read(new ByteArrayInputStream(file));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> s.paste(scene(), 0, 0, 0));
        assertTrue(e.getMessage().contains("minecraft:no_such_block"), e.getMessage());
    }

    // ==================== the shipped stand-ins (plan 4c.5 item 7) ====================

    @Test
    void theStairHouseStandInSitsInTheRealPlaceWithItsEntranceAsOrigin() {
        SceneSchematic s = SceneSchematic.fromResource("npc-stairhouse-standin.schem");

        assertEquals(32, s.width());
        assertEquals(21, s.height(), "real y 46..66");
        assertEquals(32, s.length(), "real z -515..-484: the box's 32 of the developer's 34");
        assertArrayEquals(new int[] {1400, 46, -515}, s.worldMin(), "the developer's //pos1");
        assertArrayEquals(s.local(1413, 49, -508), s.origin(), "copied standing in the entrance");
        int[] door = s.local(1413, 49, -508);
        assertEquals("minecraft:air", s.blockAt(door[0], door[1], door[2]), "the doorway is open");
        assertEquals("minecraft:air", s.blockAt(door[0], door[1] + 1, door[2]), "two blocks high");
        assertEquals("minecraft:stone_bricks", s.blockAt(door[0] - 1, door[1], door[2]), "a one-block doorway");
        assertEquals("minecraft:stone_bricks", s.blockAt(door[0] + 1, door[1], door[2]));
        int[] straight = s.local(1414, 49, -503);
        assertEquals("minecraft:air", s.blockAt(straight[0], straight[1], straight[2]), "1414 49 -503 is walkable");
        assertEquals("minecraft:stone", s.blockAt(straight[0], straight[1] - 1, straight[2]), "on the floor");
        assertTrue(s.palette().stream().anyMatch(p -> p.startsWith("minecraft:stone_brick_stairs")), "it has stairs");
    }

    @Test
    void everyStandInPastesIntoTheBoxWithBlocksTheServerKnows() {
        for (String name : List.of("npc-stairhouse-standin.schem", "npc-jagged-straight.schem", "npc-doorway.schem",
            "npc-plaza.schem")) {
            assertTrue(SceneSchematic.hasResource(name), name);
            SceneSchematic s = SceneSchematic.fromResource(name);
            assertTrue(s.width() <= ScenarioScene.SIZE && s.height() <= ScenarioScene.HEIGHT
                && s.length() <= ScenarioScene.SIZE, name + " fits the box");

            SceneSchematic.Pasted pasted = s.paste(scene(), 0, 0, 0);

            assertEquals(0, pasted.outsideTheBox(), name);
            assertTrue(pasted.placed() > 1000, name + " placed " + pasted.placed());
        }
        assertFalse(SceneSchematic.hasResource("knk-stairhouse.schem"), "the developer's schematic is not here yet");
    }

    @Test
    void aMissingResourceSaysWhichOne() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SceneSchematic.fromResource("nope.schem"));
        assertTrue(e.getMessage().contains("scenario-scenes/nope.schem"), e.getMessage());
    }

    // ==================== writing test files ====================

    private static byte[] v3(int w, int h, int l, List<String> palette, int[] blocks, int[] offset, int[] origin)
        throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int b : blocks) {
            data.write(b); // all below 128 here
        }
        return v3Raw(w, h, l, palette, data.toByteArray(), offset, origin);
    }

    private static byte[] v3Raw(int w, int h, int l, List<String> palette, byte[] data, int[] offset, int[] origin)
        throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeByte(10);
        out.writeUTF("");
        out.writeByte(10);
        out.writeUTF("Schematic");
        intTag(out, "Version", 3);
        intTag(out, "DataVersion", 5023);
        if (origin != null) {
            out.writeByte(10);
            out.writeUTF("Metadata");
            out.writeByte(10);
            out.writeUTF("WorldEdit");
            intArray(out, "Origin", origin);
            out.writeByte(0);
            out.writeByte(0);
        }
        shortTag(out, "Width", w);
        shortTag(out, "Height", h);
        shortTag(out, "Length", l);
        intArray(out, "Offset", offset);
        out.writeByte(10);
        out.writeUTF("Blocks");
        palette(out, palette);
        byteArray(out, "Data", data);
        out.writeByte(0);
        out.writeByte(0);
        out.writeByte(0);
        return gzip(raw.toByteArray());
    }

    private static void palette(DataOutputStream out, List<String> palette) throws IOException {
        out.writeByte(10);
        out.writeUTF("Palette");
        for (int i = 0; i < palette.size(); i++) {
            if (palette.indexOf(palette.get(i)) == i) {
                intTag(out, palette.get(i), i);
            } else {
                intTag(out, palette.get(i) + "#" + i, i); // a filler name, unique
            }
        }
        out.writeByte(0);
    }

    private static void intTag(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(3);
        out.writeUTF(name);
        out.writeInt(value);
    }

    private static void shortTag(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(2);
        out.writeUTF(name);
        out.writeShort(value);
    }

    private static void intArray(DataOutputStream out, String name, int... values) throws IOException {
        out.writeByte(11);
        out.writeUTF(name);
        out.writeInt(values.length);
        for (int v : values) {
            out.writeInt(v);
        }
    }

    private static void byteArray(DataOutputStream out, String name, byte[] data) throws IOException {
        out.writeByte(7);
        out.writeUTF(name);
        out.writeInt(data.length);
        out.write(data);
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }
}
