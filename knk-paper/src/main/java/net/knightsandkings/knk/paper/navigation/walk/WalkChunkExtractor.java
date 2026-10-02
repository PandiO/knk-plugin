package net.knightsandkings.knk.paper.navigation.walk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.util.BlockKey;
import net.knightsandkings.knk.paper.roads.SpanExtractor;

/**
 * Reduces one chunk's band of sections to a {@link WalkChunk} (KNG-51 {@code LAST_MILE_PATHFINDING.md}
 * §4, §8) — the "permissive {@code roadFloor}" capture: <em>every</em> material can be a floor, the
 * search's own {@code PassabilityRules.isWalkFloor} decides on the recorded material (fences, walls,
 * panes, doors and ladders are never floors there). Pure: blocks come through the road builder's
 * {@link SpanExtractor.BlockSource} (a {@code ChunkSnapshot} on the server, a fake in tests), passability
 * from the same {@link PassabilityRules} (Bukkit's collision flag + overlay patterns) the builder uses.
 *
 * <p>Per block it records passable/solid/hazard and stair-or-slab like the builder's grid, and the
 * walk-only flags the search reads through {@code WalkCells} ({@link PassabilityRules#isHandOpenableDoor},
 * the profile's climbables, {@link PassabilityRules#isWater}) — on paper {@code LADDER} and doors are
 * collidable (solid), the walk view makes them passable through these flags. A floor material is kept
 * for every solid, hazard-free block whose block above is walk-passable (passable, a door, a climbable
 * or a gate-door cell): exactly the blocks the search may ask {@code floorMaterial} about.
 *
 * <p>Not thread-safe (a per-material flag cache); one instance on the main thread.
 */
public final class WalkChunkExtractor {

    private final PassabilityRules rules;
    private final Set<String> climbables;
    private final GateCells gates;
    private final int worldMinY;
    private final int worldMaxY;
    private final Map<String, Byte> flagsByMaterial = new HashMap<>();

    /**
     * @param rules      passability (collidable + overlay patterns from config) — the builder's rules
     * @param climbables material names climbed ({@code MovementProfile.climbables})
     * @param gates      the world's gate-door cells (walk-passable headroom)
     * @param worldMinY  {@code World.getMinHeight()}
     * @param worldMaxY  {@code World.getMaxHeight()} (exclusive)
     */
    public WalkChunkExtractor(PassabilityRules rules, Set<String> climbables, GateCells gates, int worldMinY,
                              int worldMaxY) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.climbables = Set.copyOf(Objects.requireNonNull(climbables, "climbables"));
        this.gates = gates == null ? GateCells.NONE : gates;
        if (worldMinY >= worldMaxY) {
            throw new IllegalArgumentException("worldMinY must be below worldMaxY");
        }
        this.worldMinY = worldMinY;
        this.worldMaxY = worldMaxY;
    }

    public int worldMinY() {
        return worldMinY;
    }

    public int worldMaxY() {
        return worldMaxY;
    }

    /** The lowest and highest section of the world. */
    public int minSection() {
        return worldMinY >> 4;
    }

    public int maxSection() {
        return (worldMaxY - 1) >> 4;
    }

    /** The flags byte of a material ({@link WalkChunk#PASSABLE} …), cached per name. */
    public int flagsOf(String material) {
        Byte cached = flagsByMaterial.get(material);
        if (cached != null) {
            return cached & 0xFF;
        }
        int f = rules.isPassable(material) ? WalkChunk.PASSABLE : WalkChunk.SOLID;
        if (rules.isHazard(material)) {
            f |= WalkChunk.HAZARD;
        }
        if (PassabilityRules.isStairOrSlab(material)) {
            f |= WalkChunk.STAIR_OR_SLAB;
        }
        if (PassabilityRules.isHandOpenableDoor(material)) {
            f |= WalkChunk.DOOR;
        }
        if (climbables.contains(material)) {
            f |= WalkChunk.CLIMBABLE;
        }
        if (PassabilityRules.isWater(material)) {
            f |= WalkChunk.WATER;
        }
        flagsByMaterial.put(material, (byte) f);
        return f;
    }

    /**
     * Captures sections {@code fromSection..toSection} (clamped to the world) of chunk {@code (chunkX, chunkZ)}.
     *
     * @param emptySection whether a section holds only air (skips reading it; {@code s -> false} reads all)
     * @param capturedAt   the cache clock's time of the capture
     */
    public WalkChunk extract(SpanExtractor.BlockSource chunk, int chunkX, int chunkZ, int fromSection, int toSection,
                             java.util.function.IntPredicate emptySection, long capturedAt) {
        return extract(chunk, chunkX, chunkZ, fromSection, toSection, emptySection, gates, capturedAt);
    }

    /** As {@link #extract(SpanExtractor.BlockSource, int, int, int, int, java.util.function.IntPredicate, long)} with the gate cells of this request. */
    public WalkChunk extract(SpanExtractor.BlockSource chunk, int chunkX, int chunkZ, int fromSection, int toSection,
                             java.util.function.IntPredicate emptySection, GateCells gates, long capturedAt) {
        Objects.requireNonNull(chunk, "chunk");
        GateCells gateCells = gates == null ? GateCells.NONE : gates;
        int from = Math.max(fromSection, minSection());
        int to = Math.min(toSection, maxSection());
        if (from > to) {
            throw new IllegalArgumentException("no section of the world between " + fromSection + " and " + toSection);
        }
        int count = to - from + 1;
        byte[][] sections = new byte[count][];
        byte[] uniform = new byte[count];
        int airFlags = flagsOf("AIR");
        for (int i = 0; i < count; i++) {
            int s = from + i;
            if (emptySection != null && emptySection.test(s)) {
                uniform[i] = (byte) airFlags;
                continue;
            }
            byte[] data = new byte[WalkChunk.SECTION_BLOCKS];
            int baseY = s << 4;
            for (int ly = 0; ly < 16; ly++) {
                int y = baseY + ly;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int f = y < worldMinY || y >= worldMaxY ? airFlags : flagsOf(material(chunk, lx, y, lz));
                        data[(ly << 8) | (lz << 4) | lx] = (byte) f;
                    }
                }
            }
            byte first = data[0];
            boolean same = true;
            for (byte b : data) {
                if (b != first) {
                    same = false;
                    break;
                }
            }
            if (same) {
                uniform[i] = first;
            } else {
                sections[i] = data;
            }
        }

        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int lowY = Math.max(from << 4, worldMinY);
        int highY = Math.min((to << 4) + 15, worldMaxY - 1);
        List<long[]> floors = new ArrayList<>();
        List<Long> doors = new ArrayList<>();
        Map<String, Short> paletteIds = new HashMap<>();
        List<String> palette = new ArrayList<>();
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = baseX + lx;
                int z = baseZ + lz;
                for (int y = lowY; y <= highY; y++) {
                    int f = flagsAt(sections, uniform, from, x, y, z);
                    if ((f & WalkChunk.DOOR) != 0) {
                        doors.add(BlockKey.pack(x, y, z));
                    }
                    if ((f & WalkChunk.SOLID) == 0 || (f & WalkChunk.HAZARD) != 0 || y + 1 >= worldMaxY) {
                        continue;
                    }
                    int above = y + 1 <= highY ? flagsAt(sections, uniform, from, x, y + 1, z)
                        : flagsOf(material(chunk, lx, y + 1, lz));
                    boolean walkPassable = (above & (WalkChunk.PASSABLE | WalkChunk.DOOR | WalkChunk.CLIMBABLE)) != 0
                        || gateCells.doorAt(x, y + 1, z).isPresent();
                    if (!walkPassable) {
                        continue;
                    }
                    String name = material(chunk, lx, y, lz);
                    Short id = paletteIds.get(name);
                    if (id == null) {
                        id = (short) palette.size();
                        paletteIds.put(name, id);
                        palette.add(name);
                    }
                    floors.add(new long[] {BlockKey.pack(x, y, z), id});
                }
            }
        }
        floors.sort((a, b) -> Long.compare(a[0], b[0]));
        long[] floorKeys = new long[floors.size()];
        short[] floorIds = new short[floors.size()];
        for (int i = 0; i < floors.size(); i++) {
            floorKeys[i] = floors.get(i)[0];
            floorIds[i] = (short) floors.get(i)[1];
        }
        long[] doorKeys = doors.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(doorKeys);
        return new WalkChunk(chunkX, chunkZ, from, sections, uniform, floorKeys, floorIds,
            palette.toArray(String[]::new), doorKeys, capturedAt);
    }

    private static int flagsAt(byte[][] sections, byte[] uniform, int from, int x, int y, int z) {
        int s = (y >> 4) - from;
        byte[] section = sections[s];
        return section == null ? uniform[s] & 0xFF : section[WalkChunk.index(x, y, z)] & 0xFF;
    }

    private static String material(SpanExtractor.BlockSource chunk, int lx, int y, int lz) {
        String m = chunk.materialAt(lx, y, lz);
        return m == null ? "AIR" : m;
    }
}
