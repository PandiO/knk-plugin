package net.knightsandkings.knk.paper.navigation.walk;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import java.util.Set;

import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.paper.roads.SpanExtractor;
import org.junit.jupiter.api.Test;

/**
 * KNG-51 §2 row 4 / §8: memory per captured chunk and extraction time, on a synthetic overworld chunk
 * (rolling grass 60-72, stone with ores and caves down to -64, a pond, trees, a cottage). The numbers
 * go into the Phase B status block; the assertions are loose ceilings so a regression (e.g. no
 * uniform-section compression) fails, not a slow CI machine.
 */
class WalkCaptureMeasureTest {

    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;
    private static final String[] ORES = {"COAL_ORE", "IRON_ORE", "COPPER_ORE", "GRAVEL", "ANDESITE", "DIORITE"};

    /** A deterministic overworld-like chunk, chunk-local coordinates. */
    static String[][][] overworld(long seed) {
        Random random = new Random(seed);
        String[][][] blocks = new String[16][MAX_Y - MIN_Y][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int ground = 66 + (int) Math.round(4 * Math.sin(x / 4.0) + 2 * Math.cos(z / 3.0));
                for (int y = MIN_Y; y < MAX_Y; y++) {
                    String m;
                    if (y == MIN_Y) {
                        m = "BEDROCK";
                    } else if (y < 0) {
                        m = random.nextInt(40) == 0 ? "DEEPSLATE_IRON_ORE" : "DEEPSLATE";
                    } else if (y < ground - 3) {
                        m = random.nextInt(25) == 0 ? ORES[random.nextInt(ORES.length)] : "STONE";
                    } else if (y < ground) {
                        m = "DIRT";
                    } else if (y == ground) {
                        m = "GRASS_BLOCK";
                    } else if (y == ground + 1 && random.nextInt(4) == 0) {
                        m = "SHORT_GRASS";
                    } else {
                        m = "AIR";
                    }
                    blocks[x][y - MIN_Y][z] = m;
                }
            }
        }
        // two caves
        for (int i = 0; i < 2; i++) {
            int cx = 3 + random.nextInt(10);
            int cy = 10 + random.nextInt(40);
            int cz = 3 + random.nextInt(10);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = cy - 4; y <= cy + 4; y++) {
                        double d = Math.pow(x - cx, 2) / 9 + Math.pow(y - cy, 2) / 4 + Math.pow(z - cz, 2) / 16;
                        if (d < 1) {
                            blocks[x][y - MIN_Y][z] = y < cy - 2 ? "WATER" : "CAVE_AIR";
                        }
                    }
                }
            }
        }
        // a pond, a tree, a cottage with a door and a ladder to its roof
        for (int x = 1; x <= 4; x++) {
            for (int z = 10; z <= 13; z++) {
                int top = topOf(blocks, x, z);
                blocks[x][top - MIN_Y][z] = "WATER";
            }
        }
        int treeTop = topOf(blocks, 12, 3);
        for (int y = treeTop + 1; y <= treeTop + 5; y++) {
            blocks[12][y - MIN_Y][3] = "OAK_LOG";
        }
        for (int x = 10; x <= 14; x++) {
            for (int z = 1; z <= 5; z++) {
                if (blocks[x][treeTop + 5 - MIN_Y][z].equals("AIR")) {
                    blocks[x][treeTop + 5 - MIN_Y][z] = "OAK_LEAVES";
                }
            }
        }
        int floor = topOf(blocks, 9, 11);
        for (int x = 7; x <= 11; x++) {
            for (int z = 9; z <= 13; z++) {
                for (int y = floor + 1; y <= floor + 4; y++) {
                    boolean wall = x == 7 || x == 11 || z == 9 || z == 13;
                    blocks[x][y - MIN_Y][z] = y == floor + 4 ? "OAK_PLANKS" : (wall ? "COBBLESTONE" : "AIR");
                }
            }
        }
        blocks[9][floor + 1 - MIN_Y][9] = "OAK_DOOR";
        blocks[9][floor + 2 - MIN_Y][9] = "OAK_DOOR";
        for (int y = floor + 1; y <= floor + 4; y++) {
            blocks[12][y - MIN_Y][11] = "LADDER";
        }
        return blocks;
    }

    private static int topOf(String[][][] blocks, int x, int z) {
        for (int y = MAX_Y - 1; y >= MIN_Y; y--) {
            String m = blocks[x][y - MIN_Y][z];
            if (!m.equals("AIR") && !m.equals("SHORT_GRASS")) {
                return y;
            }
        }
        return MIN_Y;
    }

    private static boolean sectionEmpty(String[][][] blocks, int section) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int ly = 0; ly < 16; ly++) {
                    int y = (section << 4) + ly;
                    if (y >= MIN_Y && y < MAX_Y && !blocks[x][y - MIN_Y][z].equals("AIR")) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static long medianNanos(Runnable run, int warmup, int samples) {
        for (int i = 0; i < warmup; i++) {
            run.run();
        }
        long[] t = new long[samples];
        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            run.run();
            t[i] = System.nanoTime() - start;
        }
        Arrays.sort(t);
        return t[samples / 2];
    }

    @Test
    void memoryAndTimePerChunk() {
        String[][][] blocks = overworld(51L);
        SpanExtractor.BlockSource source = (lx, y, lz) -> blocks[lx][y - MIN_Y][lz];
        boolean[] empty = new boolean[(MAX_Y >> 4) - (MIN_Y >> 4) + 1];
        for (int s = MIN_Y >> 4; s <= (MAX_Y - 1) >> 4; s++) {
            empty[s - (MIN_Y >> 4)] = sectionEmpty(blocks, s);
        }
        PassabilityRules rules = PassabilityRules.of(
            m -> m.equals("LADDER") || PassabilityRules.curatedCollidable(m));
        WalkChunkExtractor extractor = new WalkChunkExtractor(rules, Set.of("LADDER"), GateCells.NONE, MIN_Y, MAX_Y);

        WalkChunk full = extractor.extract(source, 0, 0, MIN_Y >> 4, (MAX_Y - 1) >> 4,
            s -> empty[s - (MIN_Y >> 4)], 0L);
        // the band a leg around y 66 captures: feet ± capture-margin 16 → sections 3..5
        WalkChunk band = extractor.extract(source, 0, 0, (66 - 16) >> 4, (66 + 16) >> 4,
            s -> empty[s - (MIN_Y >> 4)], 0L);

        long fullNanos = medianNanos(() -> extractor.extract(source, 0, 0, MIN_Y >> 4, (MAX_Y - 1) >> 4,
            s -> empty[s - (MIN_Y >> 4)], 0L), 20, 41);
        long bandNanos = medianNanos(() -> extractor.extract(source, 0, 0, (66 - 16) >> 4, (66 + 16) >> 4,
            s -> empty[s - (MIN_Y >> 4)], 0L), 20, 41);

        System.out.printf("[KNG-51 Phase B] full column: %s, ~%d bytes, %.2f ms; band 3..5: %s, ~%d bytes, %.2f ms%n",
            full, full.approximateBytes(), fullNanos / 1e6, band, band.approximateBytes(), bandNanos / 1e6);

        assertTrue(full.approximateBytes() < 160_000, "full column " + full.approximateBytes());
        assertTrue(band.approximateBytes() < 24_000, "band " + band.approximateBytes());
        assertTrue(band.floorCount() >= 256, "a floor per column at least");
        assertTrue(bandNanos < 50_000_000L, "band extraction " + bandNanos / 1e6 + " ms");
    }
}
