package net.knightsandkings.knk.paper.scenario;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import net.knightsandkings.knk.core.npc.nav.snapshot.SnapshotBox;

/**
 * Reads the blocks of a Minecraft region file ({@code r.<x>.<z>.mca}, the Anvil format) without the server (link B1,
 * PandiO/knk-workspace#670): the snapshot replay places a real route's blocks in a scenario box from the files
 * {@code /knk npc nav snapshot} copied. Reads the chunks' {@code sections} with their {@code block_states} (palette and
 * packed indexes, the format since 1.18); gzip, zlib and uncompressed chunks. Chunks stored outside the file
 * ({@code .mcc}) and LZ4 chunks are skipped and counted.
 */
public final class AnvilRegion {

    private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /** Gets every non-air block in the box: real x, y, z and the block state ({@code minecraft:stone_stairs[...]}) */
    @FunctionalInterface
    public interface BlockVisitor {
        void block(int x, int y, int z, String state);
    }

    /** What a read found: blocks handed to the visitor, chunks read and chunks that could not be read. */
    public record Read(int blocks, int chunks, int skipped, List<String> problems) {
    }

    private AnvilRegion() {
    }

    /** The region's x and z from a file name {@code r.<x>.<z>.mca}. */
    public static int[] regionOf(String fileName) {
        Matcher m = NAME.matcher(fileName.substring(fileName.lastIndexOf('/') + 1));
        if (!m.matches()) {
            throw new IllegalArgumentException("not a region file name: " + fileName);
        }
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    /** Hands {@code visitor} every non-air block of the region file that lies in {@code box}. */
    public static Read blocks(String fileName, byte[] mca, SnapshotBox box, BlockVisitor visitor) {
        int[] region = regionOf(fileName);
        java.util.ArrayList<String> problems = new java.util.ArrayList<>();
        if (mca.length < 8192) {
            return new Read(0, 0, 0, List.of(fileName + " has no header (" + mca.length + " bytes)"));
        }
        ByteBuffer header = ByteBuffer.wrap(mca);
        int blocks = 0;
        int chunks = 0;
        int skipped = 0;
        for (int i = 0; i < 1024; i++) {
            int location = header.getInt(i * 4);
            int offset = location >>> 8;
            if (offset == 0) {
                continue;
            }
            int cx = region[0] * 32 + (i & 31);
            int cz = region[1] * 32 + (i >> 5);
            if (cx * 16 + 15 < box.min()[0] || cx * 16 > box.max()[0] || cz * 16 + 15 < box.min()[2]
                || cz * 16 > box.max()[2]) {
                continue;
            }
            try {
                Map<String, Object> chunk = chunk(mca, offset * 4096);
                if (chunk == null) {
                    skipped++;
                    continue;
                }
                chunks++;
                blocks += sections(chunk, cx, cz, box, visitor);
            } catch (IOException | RuntimeException e) {
                skipped++;
                problems.add(fileName + " chunk " + cx + " " + cz + ": " + e.getMessage());
            }
        }
        return new Read(blocks, chunks, skipped, problems);
    }

    /** The chunk's NBT at {@code at}, or null when it is stored elsewhere or in a compression this cannot read. */
    private static Map<String, Object> chunk(byte[] mca, int at) throws IOException {
        if (at + 5 > mca.length) {
            throw new IOException("chunk beyond the end of the file");
        }
        ByteBuffer b = ByteBuffer.wrap(mca);
        int length = b.getInt(at);
        int compression = mca[at + 4] & 0xFF;
        if ((compression & 0x80) != 0) {
            return null; // in an .mcc file beside the region file
        }
        if (length < 1 || at + 4 + length > mca.length) {
            throw new IOException("chunk length " + length + " out of the file");
        }
        InputStream raw = new ByteArrayInputStream(mca, at + 5, length - 1);
        InputStream in = switch (compression) {
            case 1 -> new GZIPInputStream(raw);
            case 2 -> new InflaterInputStream(raw);
            case 3 -> raw;
            default -> null; // LZ4 (4) or a custom one
        };
        if (in == null) {
            return null;
        }
        DataInputStream data = new DataInputStream(in);
        if (data.readUnsignedByte() != SceneSchematic.Nbt.COMPOUND) {
            throw new IOException("chunk NBT does not start with a compound");
        }
        data.readUTF();
        return SceneSchematic.Nbt.compound(data);
    }

    @SuppressWarnings("unchecked")
    private static int sections(Map<String, Object> chunk, int cx, int cz, SnapshotBox box, BlockVisitor visitor) {
        Object list = chunk.get("sections");
        if (!(list instanceof List<?> sections)) {
            return 0;
        }
        int count = 0;
        for (Object o : sections) {
            Map<String, Object> section = SceneSchematic.Nbt.map(o);
            if (section == null || !(section.get("Y") instanceof Number sy)) {
                continue;
            }
            int baseY = sy.intValue() * 16;
            if (baseY + 15 < box.min()[1] || baseY > box.max()[1]) {
                continue;
            }
            Map<String, Object> states = SceneSchematic.Nbt.map(section.get("block_states"));
            if (states == null || !(states.get("palette") instanceof List<?> palette) || palette.isEmpty()) {
                continue;
            }
            String[] names = new String[palette.size()];
            for (int p = 0; p < names.length; p++) {
                names[p] = state(SceneSchematic.Nbt.map(palette.get(p)));
            }
            long[] data = states.get("data") instanceof long[] longs ? longs : null;
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(names.length - 1));
            int perLong = 64 / bits;
            long mask = (1L << bits) - 1;
            for (int i = 0; i < 4096; i++) {
                int x = cx * 16 + (i & 15);
                int z = cz * 16 + ((i >> 4) & 15);
                int y = baseY + (i >> 8);
                if (!box.contains(x, y, z)) {
                    continue;
                }
                int index = 0;
                if (data != null) {
                    int word = i / perLong;
                    if (word >= data.length) {
                        break;
                    }
                    index = (int) ((data[word] >>> ((i % perLong) * bits)) & mask);
                }
                if (index >= names.length) {
                    continue;
                }
                String name = names[index];
                if (name == null || name.equals("minecraft:air") || name.equals("minecraft:cave_air")
                    || name.equals("minecraft:void_air")) {
                    continue;
                }
                visitor.block(x, y, z, name);
                count++;
            }
        }
        return count;
    }

    /** A palette entry as a block state string: {@code Name[key=value,...]} with the properties sorted. */
    static String state(Map<String, Object> entry) {
        if (entry == null || !(entry.get("Name") instanceof String name)) {
            return null;
        }
        Map<String, Object> props = SceneSchematic.Nbt.map(entry.get("Properties"));
        if (props == null || props.isEmpty()) {
            return name;
        }
        StringBuilder out = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, Object> p : new TreeMap<>(props).entrySet()) {
            if (!first) {
                out.append(',');
            }
            out.append(p.getKey()).append('=').append(p.getValue());
            first = false;
        }
        return out.append(']').toString();
    }
}
