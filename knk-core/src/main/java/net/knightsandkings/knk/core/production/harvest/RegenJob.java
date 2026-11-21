package net.knightsandkings.knk.core.production.harvest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A harvested block (or a felled tree's blocks) waiting to come back (PRODUCTION_KINDS § 7, PandiO/knk-workspace#134):
 * the original block data and the placeholder per position, and when to restore. Persisted on disk per server
 * ({@link #encode}), so it survives restarts and waits for its chunks to load.
 *
 * @param createdAt the harvest time (epoch ms); the API's {@code regenSeconds} counts from here
 * @param restoreAt when to restore (epoch ms)
 * @param entryKey  the harvest report entry this job belongs to (its answer sets the regeneration time), or null
 */
public record RegenJob(String id, String world, long createdAt, long restoreAt, String entryKey, List<Block> blocks) {

    /** One position: what was there and what stands in for it meanwhile (Bukkit block data strings, no spaces). */
    public record Block(int x, int y, int z, String original, String placeholder) {
        public String originalMaterial() {
            return BlockStates.material(original);
        }

        public String placeholderMaterial() {
            return BlockStates.material(placeholder);
        }
    }

    public RegenJob {
        blocks = List.copyOf(blocks);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("a regeneration job needs a block");
        }
    }

    public RegenJob withRestoreAt(long at) {
        return new RegenJob(id, world, createdAt, at, entryKey, blocks);
    }

    /** The chunks (packed with {@link HarvestAreaIndex#chunkKey}) its blocks are in. */
    public Set<Long> chunks() {
        Set<Long> chunks = new HashSet<>();
        for (Block b : blocks) {
            chunks.add(HarvestAreaIndex.chunkKey(b.x() >> 4, b.z() >> 4));
        }
        return chunks;
    }

    /** One line: {@code id TAB world TAB createdAt TAB restoreAt TAB entryKey|- TAB x y z original placeholder|...}. */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append(id).append('\t').append(world).append('\t').append(createdAt).append('\t').append(restoreAt).append('\t')
                .append(entryKey == null ? "-" : entryKey).append('\t');
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (i > 0) {
                sb.append('|');
            }
            sb.append(b.x()).append(' ').append(b.y()).append(' ').append(b.z()).append(' ').append(b.original()).append(' ')
                    .append(b.placeholder());
        }
        return sb.toString();
    }

    /** The job of one {@link #encode} line; throws {@link IllegalArgumentException} for a broken line. */
    public static RegenJob decode(String line) {
        String[] f = line.split("\t", -1);
        if (f.length != 6) {
            throw new IllegalArgumentException("expected 6 fields, got " + f.length);
        }
        try {
            List<Block> blocks = new ArrayList<>();
            for (String part : f[5].split("\\|")) {
                String[] b = part.split(" ");
                if (b.length != 5) {
                    throw new IllegalArgumentException("broken block " + part);
                }
                blocks.add(new Block(Integer.parseInt(b[0]), Integer.parseInt(b[1]), Integer.parseInt(b[2]), b[3], b[4]));
            }
            return new RegenJob(f[0], f[1], Long.parseLong(f[2]), Long.parseLong(f[3]), "-".equals(f[4]) ? null : f[4],
                    blocks);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("broken number in " + line, e);
        }
    }
}
