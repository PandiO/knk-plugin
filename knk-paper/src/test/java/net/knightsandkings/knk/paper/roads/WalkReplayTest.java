package net.knightsandkings.knk.paper.roads;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.roads.walk.WalkBudget;
import net.knightsandkings.knk.core.roads.walk.WalkGoal;
import net.knightsandkings.knk.core.roads.walk.WalkGrid;
import net.knightsandkings.knk.core.roads.walk.WalkPath;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkResult;
import net.knightsandkings.knk.core.roads.walk.WalkSearch;
import net.knightsandkings.knk.core.roads.walk.CellAccess;
import net.knightsandkings.knk.core.util.BlockKey;
import net.knightsandkings.knk.paper.navigation.walk.CapturedWalkTerrain;
import net.knightsandkings.knk.paper.navigation.walk.WalkBox;
import net.knightsandkings.knk.paper.navigation.walk.WalkChunk;
import net.knightsandkings.knk.paper.navigation.walk.WalkChunkExtractor;

/**
 * KNG-51 offline replay of one direct-mode walk search: the server's capture ({@link WalkBox},
 * {@link WalkChunkExtractor}, {@link CapturedWalkTerrain}) and {@link WalkSearch} on <b>copies</b> of the dev
 * world's region files, read with {@link RoadReplayTest}'s Anvil reader. Only runs when {@code KNK_REPLAY_DIR}
 * is set; reads {@code replay/walk.txt} and writes {@code replay/out_walk.txt}. See
 * {@code tools/road-replay/README.md}, "Walk path replay".
 *
 * <p>{@code walk.txt} keys: {@code start=x,feetY,z} (the player's location), {@code target=x,y,z} (the leg's
 * target as navigation passes it), optional {@code margin=16}, {@code max-expansions=20000},
 * {@code max-length-factor=1.75}, {@code max-length=96}, {@code detour-allowance=48}, {@code wall-cost=1.0}, {@code max-drop=3}, {@code drop-penalty=10},
 * {@code arrive-distance=4} and {@code map=y0,y1} (a top view of the box, highest cell per column in that range).
 * Gate doors and WorldGuard access are not replayed (open access); passability uses the curated collidable list.
 */
@EnabledIfEnvironmentVariable(named = "KNK_REPLAY_DIR", matches = ".+")
class WalkReplayTest {

    @Test
    void walk() throws IOException {
        Map<String, String> c = RoadReplayTest.control("walk.txt");
        double[] start = doubles(c.get("start"));
        double[] target = doubles(c.get("target"));
        int margin = Integer.parseInt(c.getOrDefault("margin", "16"));
        MovementProfile profile = MovementProfile.PLAYER
            .withDrops(Integer.parseInt(c.getOrDefault("max-drop", "3")), Double.parseDouble(c.getOrDefault("drop-penalty", "10")))
            .withClimbables(Set.of("LADDER"))
            .withWallCost(Double.parseDouble(c.getOrDefault("wall-cost", String.valueOf(MovementProfile.PLAYER.wallCost()))));
        WalkBudget live = new WalkBudget(Integer.parseInt(c.getOrDefault("max-expansions", "20000")),
            Double.parseDouble(c.getOrDefault("max-length-factor", "1.75")), Double.parseDouble(c.getOrDefault("max-length", "96")),
            Double.parseDouble(c.getOrDefault("detour-allowance", "48")), WalkBudget.DEFAULTS.startSnap(), WalkBudget.DEFAULTS.goalSnap());
        double arrive = Double.parseDouble(c.getOrDefault("arrive-distance", "4"));

        RoadReplayTest anvil = new RoadReplayTest();
        WalkBox box = WalkBox.around("world", start[0], start[1], start[2], target[0], target[1], target[2], margin, -64, 320);
        WalkChunkExtractor extractor = new WalkChunkExtractor(PassabilityRules.of(PassabilityRules::curatedCollidable),
            profile.climbables(), GateCells.NONE, -64, 320);
        List<WalkChunk> chunks = new ArrayList<>();
        for (int cx = box.minChunkX(); cx <= box.maxChunkX(); cx++) {
            for (int cz = box.minChunkZ(); cz <= box.maxChunkZ(); cz++) {
                RoadReplayTest.Chunk chunk = anvil.chunk(cx, cz);
                chunks.add(extractor.extract(chunk::at, cx, cz, box.minSection(), box.maxSection(), s -> false, 0L));
            }
        }
        CapturedWalkTerrain terrain = new CapturedWalkTerrain(chunks, GateCells.NONE, -64, 320);
        WalkGoal goal = WalkGoal.within(target[0], target[1], target[2], arrive);
        WalkRequest request = new WalkRequest(terrain.terrain(), CellAccess.OPEN, profile, start[0], start[1], start[2],
            target[0], target[1], target[2], goal, live);

        StringBuilder out = new StringBuilder();
        out.append("box ").append(box).append(", ").append(chunks.size()).append(" chunks, sections ")
            .append(box.minSection()).append("..").append(box.maxSection()).append('\n');
        out.append("straight distance ").append(String.format("%.1f", request.straightDistance()))
            .append(", length cap ").append(String.format("%.1f", live.lengthCap(request.straightDistance()))).append('\n');
        out.append(describe("live budget", new WalkSearch().find(request)));
        WalkBudget unlimited = new WalkBudget(2_000_000, 100.0, 10_000.0, 0.0, live.startSnap(), live.goalSnap());
        out.append(describe("no length cap, 2M expansions", new WalkSearch().find(request.withBudget(unlimited))));
        WalkBox tall = WalkBox.around("world", start[0], start[1], start[2], target[0], target[1], target[2], margin, -64, 320);
        out.append("outside-box reads: ").append(terrain.outsideQueries()).append(" (box ").append(tall.minY()).append("..")
            .append(tall.maxY()).append(")\n");

        if (c.containsKey("map")) {
            int[] ys = java.util.Arrays.stream(c.get("map").split(",")).mapToInt(s -> Integer.parseInt(s.trim())).toArray();
            WalkResult full = new WalkSearch().find(request.withBudget(unlimited));
            out.append(map(terrain, profile, box, ys[0], ys[1], start, target, full.path().orElse(null)));
        }
        Files.writeString(Path.of(RoadReplayTest.DIR + "replay/out_walk.txt"), out.toString(), StandardCharsets.UTF_8);
    }

    /**
     * KNG-75 step 2a: many legs at once with their cost - {@code replay/legs.txt}, one leg per line
     * {@code name;startX,feetY,startZ;targetX,floorY,targetZ}, plus {@code budgets=factor/detour/max-length/expansions,…}
     * (one column per budget; default the shipped 1.75/48/96/20000) and {@code margin}, {@code arrive-distance}. Per
     * leg: the capture box's chunk count, the offline extraction time (Anvil reader, not the live {@code ChunkSnapshot}),
     * and per budget the result (with the path length), expansions and search time (median of 5). Writes {@code replay/out_legs.txt}. Skipped without {@code legs.txt}.
     */
    @Test
    void legs() throws IOException {
        Path file = Path.of(RoadReplayTest.DIR + "replay/legs.txt");
        if (!Files.exists(file)) {
            return;
        }
        Map<String, String> c = RoadReplayTest.control("legs.txt");
        int margin = Integer.parseInt(c.getOrDefault("margin", "16"));
        double arrive = Double.parseDouble(c.getOrDefault("arrive-distance", "4"));
        String[] budgets = c.getOrDefault("budgets", "1.75/48/96/20000").split(",");
        MovementProfile profile = MovementProfile.PLAYER.withDrops(3, 10).withClimbables(Set.of("LADDER"));
        WalkChunkExtractor extractor = new WalkChunkExtractor(PassabilityRules.of(PassabilityRules::curatedCollidable),
            profile.climbables(), GateCells.NONE, -64, 320);
        RoadReplayTest anvil = new RoadReplayTest();
        StringBuilder out = new StringBuilder("leg | straight | box chunks | extract ms (offline) |");
        for (String b : budgets) {
            out.append(' ').append(b.trim()).append(": result, expansions, ms |");
        }
        out.append('\n');
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] parts = line.split(";");
            if (line.isBlank() || line.startsWith("#") || parts.length != 3) {
                continue;
            }
            double[] start = doubles(parts[1]);
            double[] target = doubles(parts[2]);
            WalkBox box = WalkBox.around("world", start[0], start[1], start[2], target[0], target[1], target[2], margin, -64, 320);
            long t0 = System.nanoTime();
            List<WalkChunk> chunks = new ArrayList<>();
            for (int cx = box.minChunkX(); cx <= box.maxChunkX(); cx++) {
                for (int cz = box.minChunkZ(); cz <= box.maxChunkZ(); cz++) {
                    RoadReplayTest.Chunk chunk = anvil.chunk(cx, cz);
                    chunks.add(extractor.extract(chunk::at, cx, cz, box.minSection(), box.maxSection(), s -> false, 0L));
                }
            }
            double extractMs = (System.nanoTime() - t0) / 1e6;
            CapturedWalkTerrain terrain = new CapturedWalkTerrain(chunks, GateCells.NONE, -64, 320);
            WalkGoal goal = WalkGoal.within(target[0], target[1], target[2], arrive);
            WalkRequest request = new WalkRequest(terrain.terrain(), CellAccess.OPEN, profile, start[0], start[1], start[2],
                target[0], target[1], target[2], goal, WalkBudget.DEFAULTS);
            out.append(parts[0]).append(" | ").append(String.format("%.1f", request.straightDistance())).append(" | ")
                .append(chunks.size()).append(" | ").append(String.format("%.0f", extractMs)).append(" |");
            for (String b : budgets) {
                double[] v = java.util.Arrays.stream(b.trim().split("/")).mapToDouble(Double::parseDouble).toArray();
                WalkBudget budget = new WalkBudget((int) v[3], v[0], v[2], v[1], WalkBudget.DEFAULTS.startSnap(),
                    WalkBudget.DEFAULTS.goalSnap());
                WalkRequest r = request.withBudget(budget);
                WalkResult result = null;
                double[] times = new double[5];
                for (int i = 0; i < times.length; i++) {
                    long s = System.nanoTime();
                    result = new WalkSearch().find(r);
                    times[i] = (System.nanoTime() - s) / 1e6;
                }
                java.util.Arrays.sort(times);
                out.append(' ').append(result.status()).append(result.path().map(p -> String.format(" %.0f", p.length())).orElse(""))
                    .append(", ").append(result.expansions()).append(", ").append(String.format("%.1f", times[2])).append(" |");
            }
            out.append('\n');
        }
        Files.writeString(Path.of(RoadReplayTest.DIR + "replay/out_legs.txt"), out.toString(), StandardCharsets.UTF_8);
    }

    private static String describe(String label, WalkResult r) {
        StringBuilder sb = new StringBuilder("\n== ").append(label).append(": ").append(r.status()).append(" (")
            .append(r.reason()).append(", ").append(r.expansions()).append(" expansions)\n");
        r.path().ifPresent(p -> {
            sb.append("  length ").append(String.format("%.1f", p.length())).append(", cost ").append(String.format("%.1f", p.cost()))
                .append(", ").append(p.size()).append(" cells\n  ");
            for (int i = 0; i < p.size(); i++) {
                long k = p.cell(i);
                sb.append(BlockKey.x(k)).append(',').append(p.floorY(i)).append(',').append(BlockKey.z(k)).append(p.isLadder(i) ? "L" : "").append(' ');
                if (i % 10 == 9) sb.append("\n  ");
            }
            sb.append('\n');
        });
        return sb.toString();
    }

    /** Top view: per column the highest walk cell's floor y in y0..y1 (last digit), 'S'/'T' start/target, '*' path. */
    private static String map(CapturedWalkTerrain terrain, MovementProfile profile, WalkBox box, int y0, int y1,
                              double[] start, double[] target, WalkPath path) {
        WalkGrid grid = WalkSearch.gridFor(terrain.terrain(), profile);
        java.util.Set<Long> onPath = new java.util.HashSet<>();
        if (path != null) {
            for (int i = 0; i < path.size(); i++) {
                onPath.add(BlockKey.pack(BlockKey.x(path.cell(i)), 0, BlockKey.z(path.cell(i))));
            }
        }
        StringBuilder sb = new StringBuilder("\nmap: highest walk cell floor y (last digit) in " + y0 + ".." + y1
            + "; '.' none, '*' path, S start, T target\n       ");
        for (int x = box.minX(); x <= box.maxX(); x++) sb.append(Math.floorMod(x, 10) == 0 ? (char) ('0' + Math.floorMod(x / 10, 10)) : ' ');
        sb.append('\n');
        int sx = (int) Math.floor(start[0]), sz = (int) Math.floor(start[2]);
        int tx = (int) Math.floor(target[0]), tz = (int) Math.floor(target[2]);
        for (int z = box.minZ(); z <= box.maxZ(); z++) {
            sb.append(String.format("%6d ", z));
            for (int x = box.minX(); x <= box.maxX(); x++) {
                char ch = '.';
                for (int y = y1; y >= y0; y--) {
                    if (grid.isCell(x, y, z)) {
                        ch = (char) ('0' + Math.floorMod(y, 10));
                        break;
                    }
                }
                if (onPath.contains(BlockKey.pack(x, 0, z))) ch = '*';
                if (x == sx && z == sz) ch = 'S';
                if (x == tx && z == tz) ch = 'T';
                sb.append(ch);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static double[] doubles(String csv) {
        return java.util.Arrays.stream(csv.split(",")).mapToDouble(s -> Double.parseDouble(s.trim())).toArray();
    }
}
