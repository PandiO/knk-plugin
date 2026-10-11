package net.knightsandkings.knk.paper.roads;

import java.util.Objects;
import java.util.function.Predicate;

import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;

/**
 * Scans one chunk for candidate road spans and writes them into {@link CompactSpans} (DESIGN §5.2, §9):
 * a span is a solid, non-hazard, non-overlay floor whose material is a floor material of some road
 * profile, with two passable blocks above it (gate-door cells count as passable, DESIGN §5.4). Per span
 * it records the stair/slab flag, whether the third block above is passable (step-up head room) and
 * whether a gate door touches it. Pure: the chunk is read through {@link BlockSource}, so the real
 * {@code ChunkSnapshot} and a test fake look alike.
 */
public final class SpanExtractor {

    /** The material name (upper case, {@code Material.name()}) at chunk-local {@code (lx, y, lz)}. */
    @FunctionalInterface
    public interface BlockSource {
        String materialAt(int lx, int y, int lz);
    }

    private final PassabilityRules rules;
    private final Predicate<String> roadFloor;
    private final GateCells gates;
    private final int minY;
    private final int maxY; // exclusive

    /**
     * @param rules     passability (collidable + overlay patterns from config)
     * @param roadFloor whether a material is a Surface/Edge/Accent material of any enabled profile
     * @param gates     the world's gate-door cells (closed footprints)
     * @param minY      the world's lowest block
     * @param maxY      the world's height limit, exclusive
     */
    public SpanExtractor(PassabilityRules rules, Predicate<String> roadFloor, GateCells gates, int minY, int maxY) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.roadFloor = Objects.requireNonNull(roadFloor, "roadFloor");
        this.gates = gates == null ? GateCells.NONE : gates;
        this.minY = minY;
        this.maxY = maxY;
    }

    /** Extracts chunk {@code (chunkX, chunkZ)} into {@code target}; returns the number of spans found. */
    public int extract(BlockSource chunk, int chunkX, int chunkZ, CompactSpans target) {
        CompactSpans.ChunkBuilder builder = target.chunk(chunkX, chunkZ);
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = baseX + lx;
                int z = baseZ + lz;
                // Walk the column once; a span needs y+2 < maxY.
                String above2 = materialOrAir(chunk, lx, minY + 1, lz);
                String above1 = materialOrAir(chunk, lx, minY, lz);
                for (int y = minY; y + 2 < maxY; y++) {
                    String here = above1;
                    above1 = above2;
                    above2 = materialOrAir(chunk, lx, y + 2, lz);
                    if (!roadFloor.test(here) || rules.isOverlay(here) || rules.isHazard(here) || !rules.isSolid(here)) {
                        continue;
                    }
                    if (!passable(above1, x, y + 1, z) || !passable(above2, x, y + 2, z)) {
                        continue;
                    }
                    int flags = 0;
                    if (PassabilityRules.isStairOrSlab(here)) {
                        flags |= CompactSpans.FLAG_STAIR_OR_SLAB;
                    }
                    if (y + 3 < maxY && passable(materialOrAir(chunk, lx, y + 3, lz), x, y + 3, z)) {
                        flags |= CompactSpans.FLAG_THIRD_PASSABLE;
                    }
                    if (gates.doorAt(x, y, z).isPresent() || gates.doorAt(x, y + 1, z).isPresent() || gates.doorAt(x, y + 2, z).isPresent()) {
                        flags |= CompactSpans.FLAG_GATE;
                    }
                    builder.add(x, y, z, here, flags);
                }
            }
        }
        return builder.build();
    }

    /** Air, a non-collidable block, an overlay, or a gate-door cell; never a hazard. */
    boolean passable(String material, int x, int y, int z) {
        if (rules.isHazard(material)) {
            return false;
        }
        return rules.isPassable(material) || gates.doorAt(x, y, z).isPresent();
    }

    private static String materialOrAir(BlockSource chunk, int lx, int y, int lz) {
        String m = chunk.materialAt(lx, y, lz);
        return m == null ? "AIR" : m;
    }
}
