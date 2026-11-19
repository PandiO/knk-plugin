package net.knightsandkings.knk.paper.navigation.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.snapshot.NavSnapshotZip;
import net.knightsandkings.knk.core.npc.nav.snapshot.SnapshotBox;
import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;
import net.knightsandkings.knk.core.roads.walk.MovementProfile;
import net.knightsandkings.knk.core.roads.walk.WalkBudget;
import net.knightsandkings.knk.core.roads.walk.WalkRecomputeHint;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkResult;
import net.knightsandkings.knk.core.roads.walk.WalkSearch;
import net.knightsandkings.knk.core.util.BlockKey;
import net.knightsandkings.knk.paper.roads.SpanExtractor;
import net.knightsandkings.knk.paper.scenario.AnvilRegion;

/**
 * PandiO/knk-workspace#771 items 1 and 2 on the developer's dev world: the NPC navigation snapshots of the keep
 * ({@code scenario-snapshots/*.zip}, taken 2026-11-17) read back into walk captures as the server captures chunks, then
 * searched as the phase 0 runs did (run 2's limits: {@code max-length} 1000, {@code detour-allowance} 600).
 */
class WalkDevWorldFixesTest {

    /** Run 2 of the measurements (option A): the limits the scenes were walked with. */
    private static final WalkBudget RUN2 = new WalkBudget(200_000, 1.75, 1000, 600, 5, 2, 3);

    /** Location 56, "Keep storage hall": 1421 73 -488, floor 72. */
    private static final double[] HALL = {1421.5, 72, -487.5};
    /** Where the player got stuck in every scene 1 run (feet). */
    private static final double[] STUCK = {1417.5, 59, -488.5};

    private static Map<Long, String> load(String zip) throws Exception {
        NavSnapshotZip.Loaded loaded;
        try (InputStream in = WalkDevWorldFixesTest.class.getResourceAsStream(zip)) {
            loaded = NavSnapshotZip.read(in);
        }
        var m = loaded.snapshot().manifest();
        SnapshotBox box = new SnapshotBox(m.boxMin(), m.boxMax());
        Map<Long, String> states = new HashMap<>();
        for (Map.Entry<String, byte[]> file : loaded.files().entrySet()) {
            AnvilRegion.blocks(file.getKey(), file.getValue(), box,
                (x, y, z, state) -> states.put(BlockKey.pack(x, y, z), state));
        }
        return states;
    }

    private static String material(String state) {
        if (state == null) {
            return "AIR";
        }
        String s = state.startsWith("minecraft:") ? state.substring("minecraft:".length()) : state;
        int bracket = s.indexOf('[');
        return (bracket < 0 ? s : s.substring(0, bracket)).toUpperCase(Locale.ROOT);
    }

    /** The capture of {@code box} as the server makes it; {@code withStates} false: material names only (before #771). */
    private static CapturedWalkTerrain capture(Map<Long, String> states, WalkBox box, boolean withStates) {
        WalkChunkExtractor extractor = new WalkChunkExtractor(PassabilityRules.defaults(),
            MovementProfile.PLAYER.climbables(), GateCells.NONE, -64, 320);
        List<WalkChunk> chunks = new ArrayList<>();
        for (int cx = box.minChunkX(); cx <= box.maxChunkX(); cx++) {
            for (int cz = box.minChunkZ(); cz <= box.maxChunkZ(); cz++) {
                int bx = cx << 4;
                int bz = cz << 4;
                chunks.add(extractor.extract(new SpanExtractor.BlockSource() {
                    @Override
                    public String materialAt(int lx, int y, int lz) {
                        return material(states.get(BlockKey.pack(bx + lx, y, bz + lz)));
                    }

                    @Override
                    public boolean isOpen(int lx, int y, int lz) {
                        String state = states.get(BlockKey.pack(bx + lx, y, bz + lz));
                        return withStates && state != null && state.contains("open=true");
                    }
                }, cx, cz, box.minSection(), box.maxSection(), s -> false, 0L));
            }
        }
        return new CapturedWalkTerrain(chunks, GateCells.NONE, -64, 320);
    }

    private static WalkRequest request(CapturedWalkTerrain terrain, double[] feet, double[] targetFloor) {
        return WalkRequest.toPoint(terrain.terrain(), feet[0], feet[1], feet[2], targetFloor[0], targetFloor[1],
            targetFloor[2], 1.5).withBudget(RUN2);
    }

    // ===== item 1: scene 1, spawn to the keep storage hall =====

    @Test
    void scene1ARecomputeFromTheStuckSpotRefindsTheWayWithTheFoundPathsHint() throws Exception {
        Map<Long, String> keep = load("/scenario-snapshots/quarry-to-keep-stairhouse.zip");
        // the first search, from the snapshot's edge on the way from spawn (1443 43 -567), finds the way: up the keep
        // stair house's lower flights (past the stuck spot), out south and round through the courtyard, ~400 blocks
        WalkBox whole = new WalkBox("world_KNK-DEV", 1391, 36, -559, 1454, 96, -480);
        WalkResult first = new WalkSearch().find(request(capture(keep, whole, true), new double[] {1443.5, 43, -557.5},
            HALL));
        assertEquals(WalkResult.Status.FOUND, first.status(), first.toString());

        // a recompute from the stuck spot, 13 blocks below the hall, captures the box round player and hall: the way
        // runs ~60 blocks south of it, so the search closes inside the box (the runs' NO_PATH, margin 16 or 48)
        for (int margin : new int[] {16, 48}) {
            WalkBox box = WalkBox.around("world_KNK-DEV", STUCK[0], STUCK[1], STUCK[2], HALL[0], HALL[1], HALL[2],
                margin, -64, 320);
            WalkResult today = new WalkSearch().find(request(capture(keep, box, true), STUCK, HALL));
            assertEquals(WalkResult.Status.NO_PATH, today.status(), "margin " + margin + ": " + today);

            WalkRecomputeHint hint = WalkRecomputeHint.of(first.path().orElseThrow().points(), STUCK[0], STUCK[1] - 1,
                STUCK[2]).orElseThrow();
            WalkBox grown = box.including(hint.bounds(), WalkLegPreparer.HINT_MARGIN, -64, 320);
            WalkRequest plain = request(capture(keep, grown, true), STUCK, HALL);
            WalkResult fixed = new WalkSearch().find(
                plain.withBudget(hint.widen(RUN2, plain.straightDistance(), plain.heightDifference())));
            assertEquals(WalkResult.Status.FOUND, fixed.status(), "margin " + margin + ": " + fixed);
            assertTrue(grown.chunkCount() <= 64, grown.chunkCount() + " chunks");
        }
    }

    @Test
    void scene1WithTheShippedLimitsTheHintAlsoLiftsTheLengthCap() throws Exception {
        Map<Long, String> keep = load("/scenario-snapshots/quarry-to-keep-stairhouse.zip");
        WalkBox whole = new WalkBox("world_KNK-DEV", 1391, 36, -559, 1454, 96, -480);
        WalkResult first = new WalkSearch().find(request(capture(keep, whole, true), new double[] {1443.5, 43, -557.5},
            HALL));
        WalkRecomputeHint hint = WalkRecomputeHint.of(first.path().orElseThrow().points(), STUCK[0], STUCK[1] - 1,
            STUCK[2]).orElseThrow();
        WalkBox box = WalkBox.around("world_KNK-DEV", STUCK[0], STUCK[1], STUCK[2], HALL[0], HALL[1], HALL[2], 16, -64, 320)
            .including(hint.bounds(), WalkLegPreparer.HINT_MARGIN, -64, 320);
        WalkRequest shipped = request(capture(keep, box, true), STUCK, HALL).withBudget(WalkBudget.DEFAULTS);

        WalkResult today = new WalkSearch().find(shipped);
        assertEquals(WalkResult.Status.FALLBACK, today.status(), "the cap of a 17-block leg: " + today);
        WalkResult fixed = new WalkSearch().find(shipped.withBudget(
            hint.widen(WalkBudget.DEFAULTS, shipped.straightDistance(), shipped.heightDifference())));
        assertEquals(WalkResult.Status.FOUND, fixed.status(), fixed.toString());
    }

    // ===== item 2: the Garrison Watchtower Roof's hatch =====

    /** Location 53, "Garrison Watchtower Roof": 1401 107 -542 (feet); its hatch is the open trapdoor at 1400 107 -540. */
    private static final double[] ROOF = {1401.5, 107, -541.5};
    /** Location 52, "Keep Tower Roof": 1411 113 -504 (feet). */
    private static final double[] TOWER = {1411.5, 113, -503.5};

    @Test
    void scene8TheWatchtowerHatchIsPassedDownAndUp() throws Exception {
        Map<Long, String> tower = load("/scenario-snapshots/keep-tower.zip");
        assertTrue(tower.get(BlockKey.pack(1400, 107, -540)).contains("trapdoor"));
        assertTrue(tower.get(BlockKey.pack(1400, 107, -540)).contains("open=true"));
        assertEquals("minecraft:ladder", tower.get(BlockKey.pack(1400, 106, -540)).replaceAll("\\[.*", ""));
        WalkBox whole = new WalkBox("world_KNK-DEV", 1391, 66, -559, 1454, 130, -480);

        CapturedWalkTerrain before = capture(tower, whole, false);
        WalkResult shutIn = new WalkSearch().find(request(before, ROOF, floorOf(TOWER)));
        assertEquals(WalkResult.Status.NO_PATH, shutIn.status(), shutIn.toString());
        assertEquals(44, shutIn.stats().cells(), "the runs' 44 cells: the roof alone");

        CapturedWalkTerrain after = capture(tower, whole, true);
        WalkResult down = new WalkSearch().find(request(after, ROOF, floorOf(TOWER)));
        assertEquals(WalkResult.Status.FOUND, down.status(), down.toString());
        WalkResult up = new WalkSearch().find(request(after, TOWER, floorOf(ROOF)));
        assertEquals(WalkResult.Status.FOUND, up.status(), up.toString());
    }

    private static double[] floorOf(double[] feet) {
        return new double[] {feet[0], feet[1] - 1, feet[2]};
    }
}
