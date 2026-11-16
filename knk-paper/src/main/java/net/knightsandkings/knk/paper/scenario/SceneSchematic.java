package net.knightsandkings.knk.paper.scenario;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * A scenario scene from a WorldEdit schematic (implementation plan 4c.4 item 1, link M1, PandiO/knk-workspace#621): a
 * {@code .schem} file in the plugin's {@code scenario-scenes/} resources is read and pasted into a scenario's box, so
 * a real place from the developer's world (cut with {@code //copy} and {@code //schem save}) becomes a scene.
 * <p>
 * Reads the Sponge schematic format WorldEdit saves ({@code sponge.3}, WorldEdit 7.3 and later, and the older
 * {@code sponge.2}): the gzipped NBT with {@code Width}, {@code Height}, {@code Length}, the block palette of
 * block-state strings and the varint block data. Block entities (sign texts, chest contents), entities and biomes are
 * left out: a scene needs the geometry. The reader is plain Java, so it needs neither WorldEdit's platform nor a
 * server; {@link #paste} turns the palette into Bukkit block data on the main thread. A schematic saved on another
 * Minecraft version than the server's can name blocks the server does not know: pasting it fails with the name.
 * <p>
 * Positions inside the schematic are local: x 0 to {@code width - 1} and so on from its lowest corner. Where WorldEdit
 * wrote them, {@link #worldMin()} is the real position of that corner and {@link #origin()} the local position of the
 * player who copied it, so a place the developer named (1414 49 -503) is found in the scene with {@link #local}.
 */
public final class SceneSchematic {

    /** The resource folder the scenes live in, inside the plugin jar. */
    public static final String RESOURCE_FOLDER = "scenario-scenes/";

    private final int width;
    private final int height;
    private final int length;
    private final List<String> palette;
    private final int[] blocks;
    private final int[] worldMin;
    private final int[] origin;
    private final int dataVersion;

    SceneSchematic(int width, int height, int length, List<String> palette, int[] blocks, int[] worldMin, int[] origin,
                   int dataVersion) {
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new IllegalArgumentException("a schematic needs a size, was " + width + " x " + height + " x " + length);
        }
        if (blocks.length != width * height * length) {
            throw new IllegalArgumentException("block data has " + blocks.length + " blocks, the size needs "
                + width * height * length);
        }
        for (int index : blocks) {
            if (index < 0 || index >= palette.size()) {
                throw new IllegalArgumentException("block data names palette entry " + index + " of " + palette.size());
            }
        }
        this.width = width;
        this.height = height;
        this.length = length;
        this.palette = List.copyOf(palette);
        this.blocks = blocks.clone();
        this.worldMin = worldMin.clone();
        this.origin = origin.clone();
        this.dataVersion = dataVersion;
    }

    /** Reads a scene from the plugin's {@value #RESOURCE_FOLDER} resources, for example {@code "npc-plaza.schem"}. */
    public static SceneSchematic fromResource(String name) {
        String path = RESOURCE_FOLDER + name;
        try (InputStream in = SceneSchematic.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("no scene resource " + path + " in the plugin jar");
            }
            return read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the scene resource " + path, e);
        }
    }

    /** Whether the plugin jar has a scene resource of that name (the developer's schematic, once committed). */
    public static boolean hasResource(String name) {
        return SceneSchematic.class.getClassLoader().getResource(RESOURCE_FOLDER + name) != null;
    }

    /** Reads a Sponge schematic (version 2 or 3, gzipped NBT) from a stream; the stream is not closed. */
    public static SceneSchematic read(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(new GZIPInputStream(in));
        int type = data.readUnsignedByte();
        if (type != Nbt.COMPOUND) {
            throw new IOException("not a schematic: the file does not start with an NBT compound");
        }
        data.readUTF(); // the root's name: "" in version 3, "Schematic" in version 2
        Map<String, Object> root = Nbt.compound(data);
        Map<String, Object> schematic = root.containsKey("Schematic") ? Nbt.map(root.get("Schematic")) : root;
        int version = Nbt.integer(schematic, "Version", 1);
        Map<String, Object> blockSection;
        String dataKey;
        if (version >= 3) {
            blockSection = Nbt.map(schematic.get("Blocks"));
            dataKey = "Data";
            if (blockSection == null) {
                throw new IOException("a version " + version + " schematic without Blocks");
            }
        } else if (version == 2) {
            blockSection = schematic;
            dataKey = "BlockData";
        } else {
            throw new IOException("schematic version " + version + " is not read (WorldEdit's sponge.2 or sponge.3 only)");
        }
        int width = Nbt.integer(schematic, "Width", 0);
        int height = Nbt.integer(schematic, "Height", 0);
        int length = Nbt.integer(schematic, "Length", 0);
        Map<String, Object> paletteTag = Nbt.map(blockSection.get("Palette"));
        if (paletteTag == null) {
            throw new IOException("a schematic without a block palette");
        }
        String[] palette = new String[paletteTag.size()];
        for (Map.Entry<String, Object> entry : paletteTag.entrySet()) {
            int index = ((Number) entry.getValue()).intValue();
            if (index < 0 || index >= palette.length || palette[index] != null) {
                throw new IOException("palette index " + index + " for " + entry.getKey() + " is out of order");
            }
            palette[index] = entry.getKey();
        }
        Object raw = blockSection.get(dataKey);
        if (!(raw instanceof byte[] bytes)) {
            throw new IOException("a schematic without " + dataKey);
        }
        int[] blocks = new int[width * height * length];
        int at = 0;
        for (int i = 0; i < blocks.length; i++) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (at >= bytes.length) {
                    throw new IOException("block data ends after " + i + " of " + blocks.length + " blocks");
                }
                int b = bytes[at++];
                value |= (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IOException("a block's palette index is too long a varint");
                }
            }
            blocks[i] = value;
        }
        int[] offset = Nbt.ints(schematic.get("Offset"));
        if (offset == null || offset.length != 3) {
            offset = new int[] {0, 0, 0};
        }
        int[] worldMin = {0, 0, 0};
        int[] origin = {0, 0, 0};
        Map<String, Object> metadata = Nbt.map(schematic.get("Metadata"));
        if (version >= 3) {
            // WorldEdit 7.3+: Offset is the lowest corner minus the copy origin, Metadata.WorldEdit.Origin the origin
            origin = new int[] {-offset[0], -offset[1], -offset[2]};
            Map<String, Object> worldEdit = metadata == null ? null : Nbt.map(metadata.get("WorldEdit"));
            int[] real = worldEdit == null ? null : Nbt.ints(worldEdit.get("Origin"));
            if (real != null && real.length == 3) {
                worldMin = new int[] {real[0] + offset[0], real[1] + offset[1], real[2] + offset[2]};
            }
        } else {
            // version 2: Offset is the real lowest corner, Metadata.WEOffsetX.. the corner minus the copy origin
            worldMin = offset;
            if (metadata != null && metadata.containsKey("WEOffsetX")) {
                origin = new int[] {-Nbt.integer(metadata, "WEOffsetX", 0), -Nbt.integer(metadata, "WEOffsetY", 0),
                    -Nbt.integer(metadata, "WEOffsetZ", 0)};
            }
        }
        return new SceneSchematic(width, height, length, List.of(palette), blocks, worldMin, origin,
            Nbt.integer(schematic, "DataVersion", 0));
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int length() {
        return length;
    }

    /** The block-state strings of the palette, in index order. */
    public List<String> palette() {
        return palette;
    }

    /** The block state at a local position, as WorldEdit wrote it (for example {@code minecraft:stone_bricks}). */
    public String blockAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ", " + z + ") is outside the schematic");
        }
        return palette.get(blocks[x + z * width + y * width * length]);
    }

    /** The real position of the schematic's lowest corner where WorldEdit wrote it, else 0 0 0. */
    public int[] worldMin() {
        return worldMin.clone();
    }

    /** The local position of the player who copied it where WorldEdit wrote it, else 0 0 0. */
    public int[] origin() {
        return origin.clone();
    }

    /** A real block position (where it was copied) as a local position in the schematic. */
    public int[] local(int realX, int realY, int realZ) {
        return new int[] {realX - worldMin[0], realY - worldMin[1], realZ - worldMin[2]};
    }

    /** The Minecraft data version it was saved with; 0 when it does not say. */
    public int dataVersion() {
        return dataVersion;
    }

    /** What {@link #paste} placed and what it left out. */
    public record Pasted(int placed, int outsideTheBox) {
    }

    /**
     * Pastes the schematic into a scenario's box with its lowest corner at box position {@code (bx, by, bz)}, without
     * physics. Air is not placed (the box is cleared before a scenario), and blocks that fall outside the box are left
     * out and counted, so a place a little larger than the box can still be a scene. Main thread.
     *
     * @throws IllegalArgumentException a palette entry the server does not know as a block, with its name
     */
    public Pasted paste(ScenarioScene scene, int bx, int by, int bz) {
        Objects.requireNonNull(scene, "scene");
        BlockData[] data = blockData();
        int placed = 0;
        int outside = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    BlockData block = data[blocks[x + z * width + y * width * length]];
                    if (block == null) {
                        continue;
                    }
                    int sx = bx + x;
                    int sy = by + y;
                    int sz = bz + z;
                    if (sx < 0 || sy < 0 || sz < 0 || sx >= scene.box().x() || sy >= scene.box().y()
                        || sz >= scene.box().z()) {
                        outside++;
                        continue;
                    }
                    Block target = scene.block(sx, sy, sz);
                    target.setBlockData(block, false);
                    placed++;
                }
            }
        }
        return new Pasted(placed, outside);
    }

    /** The palette as Bukkit block data, null for air. */
    private BlockData[] blockData() {
        BlockData[] data = new BlockData[palette.size()];
        for (int i = 0; i < data.length; i++) {
            String state = palette.get(i);
            BlockData block;
            try {
                block = Bukkit.createBlockData(state);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("the scene names a block this server does not know: " + state, e);
            }
            data[i] = block.getMaterial() == Material.AIR || block.getMaterial() == Material.CAVE_AIR
                || block.getMaterial() == Material.VOID_AIR ? null : block;
        }
        return data;
    }

    /** The NBT a schematic is made of: just enough to read one. */
    static final class Nbt {

        static final int END = 0;
        static final int COMPOUND = 10;

        private Nbt() {
        }

        static Map<String, Object> compound(DataInputStream in) throws IOException {
            Map<String, Object> out = new HashMap<>();
            while (true) {
                int type = in.readUnsignedByte();
                if (type == END) {
                    return out;
                }
                String name = in.readUTF();
                out.put(name, payload(in, type));
            }
        }

        private static Object payload(DataInputStream in, int type) throws IOException {
            return switch (type) {
                case 1 -> in.readByte();
                case 2 -> in.readShort();
                case 3 -> in.readInt();
                case 4 -> in.readLong();
                case 5 -> in.readFloat();
                case 6 -> in.readDouble();
                case 7 -> {
                    int n = length(in);
                    byte[] bytes = new byte[n];
                    in.readFully(bytes);
                    yield bytes;
                }
                case 8 -> in.readUTF();
                case 9 -> {
                    int element = in.readUnsignedByte();
                    int n = length(in);
                    List<Object> list = new ArrayList<>(Math.min(n, 1024));
                    for (int i = 0; i < n; i++) {
                        list.add(payload(in, element));
                    }
                    yield list;
                }
                case COMPOUND -> compound(in);
                case 11 -> {
                    int n = length(in);
                    int[] ints = new int[n];
                    for (int i = 0; i < n; i++) {
                        ints[i] = in.readInt();
                    }
                    yield ints;
                }
                case 12 -> {
                    int n = length(in);
                    long[] longs = new long[n];
                    for (int i = 0; i < n; i++) {
                        longs[i] = in.readLong();
                    }
                    yield longs;
                }
                default -> throw new IOException("unknown NBT tag type " + type);
            };
        }

        private static int length(DataInputStream in) throws IOException {
            int n = in.readInt();
            if (n < 0 || n > 64 * 1024 * 1024) {
                throw new IOException("an NBT array of " + n + " elements");
            }
            return n;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> map(Object value) {
            return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        }

        static int integer(Map<String, Object> tag, String key, int fallback) {
            return tag.get(key) instanceof Number n ? n.intValue() : fallback;
        }

        static int[] ints(Object value) {
            return value instanceof int[] ints ? ints : null;
        }
    }
}
