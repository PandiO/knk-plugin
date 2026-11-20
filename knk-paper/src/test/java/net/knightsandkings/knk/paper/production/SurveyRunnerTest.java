package net.knightsandkings.knk.paper.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.ProductionSurveyApi;
import net.knightsandkings.knk.core.production.survey.AreaShape;
import net.knightsandkings.knk.core.production.survey.AreaSpec;
import net.knightsandkings.knk.core.production.survey.BlockSnapshotSource;
import net.knightsandkings.knk.core.production.survey.PlaceholderOverlay;
import net.knightsandkings.knk.core.production.survey.SurveyCounter;
import net.knightsandkings.knk.core.production.survey.SurveyDue;
import net.knightsandkings.knk.core.production.survey.SurveySettings;
import net.knightsandkings.knk.core.production.survey.SurveyUnit;

/**
 * The survey runner (PandiO/knk-workspace#679, A10): the main-thread budget, Scheduled surveys waiting for loaded chunks,
 * requested ones loading them, the caps, the NotableChange trigger, the shared split from the queue's overlaps and API
 * refusals. A fake world: stone floor at y 60 in every generated chunk.
 */
class SurveyRunnerTest {

    private static final String PROFILE = "{\"unitTypes\":[\"BLOCK\",\"ENTITY\"]}";

    /** Chunks 0..3 × 0..3 are generated; {@code loaded} of them are loaded. */
    private final class FakeWorld implements SurveyWorld {
        final Set<Long> loaded = new HashSet<>();
        final List<String> captures = new ArrayList<>();
        int loads;

        @Override
        public String name() {
            return "world";
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return 128;
        }

        @Override
        public boolean isLoaded(int chunkX, int chunkZ) {
            return loaded.contains(key(chunkX, chunkZ));
        }

        @Override
        public BlockSnapshotSource.ChunkBlocks capture(int chunkX, int chunkZ) {
            captures.add(chunkX + "," + chunkZ + "@" + tick);
            return stone();
        }

        @Override
        public CompletableFuture<BlockSnapshotSource.ChunkBlocks> loadAndCapture(int chunkX, int chunkZ) {
            loads++;
            boolean generated = chunkX >= 0 && chunkX < 4 && chunkZ >= 0 && chunkZ < 4;
            return CompletableFuture.completedFuture(generated ? stone() : null);
        }

        @Override
        public List<SurveyCounter.EntitySighting> entities(AreaShape shape) {
            return List.of(new SurveyCounter.EntitySighting("SHEEP", 1, 61, 1));
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static BlockSnapshotSource.ChunkBlocks stone() {
        return new BlockSnapshotSource.ChunkBlocks() {
            public String material(int x, int y, int z) {
                return y == 60 ? "STONE" : "AIR";
            }

            public String biome(int x, int y, int z) {
                return "PLAINS";
            }

            public boolean waterSource(int x, int y, int z) {
                return false;
            }

            public int highestY(int x, int z) {
                return 60;
            }
        };
    }

    private final class FakeApi implements ProductionSurveyApi {
        final List<Integer> areas = new ArrayList<>();
        final List<Report> reports = new ArrayList<>();
        Throwable refuse;

        public CompletableFuture<List<SurveyDue>> surveyDue(int limit) {
            return CompletableFuture.completedFuture(List.of());
        }

        public CompletableFuture<ReportResult> report(int areaId, Report report) {
            if (refuse != null) {
                return CompletableFuture.failedFuture(refuse);
            }
            areas.add(areaId);
            reports.add(report);
            return CompletableFuture.completedFuture(new ReportResult(99, 1, 1, 0));
        }

        public CompletableFuture<List<AreaSpec>> requestSurvey(int structureId, Integer areaId) {
            return CompletableFuture.completedFuture(List.of());
        }

        public CompletableFuture<List<AreaSpec>> areas() {
            return CompletableFuture.completedFuture(List.of());
        }
    }

    private final FakeWorld world = new FakeWorld();
    private final FakeApi api = new FakeApi();
    private final List<SurveyRunner.Outcome> outcomes = new ArrayList<>();
    private int tick;

    private SurveyRunner runner(SurveySettings settings) {
        SurveyRunner runner = new SurveyRunner(settings, api, name -> name.equals("world") ? world : null,
                (w, id) -> id.equals("pen") ? Optional.of(new AreaShape.Cuboid(0, 60, 0, 3, 62, 3)) : Optional.empty(),
                PlaceholderOverlay.NONE, Runnable::run, Runnable::run, Logger.getAnonymousLogger(),
                Clock.fixed(Instant.parse("2026-11-19T22:00:00Z"), ZoneOffset.UTC));
        runner.addListener(outcomes::add);
        return runner;
    }

    private static SurveySettings settings(int chunksPerTick, long maxBlocks, boolean scheduledLoads) {
        return new SurveySettings(true, 5, 20, chunksPerTick, maxBlocks, 256, scheduledLoads, 64, 30, 10);
    }

    private static AreaSpec cuboid(int id, int structure, int maxX, int maxZ) {
        return new AreaSpec(id, structure, "Quarry", "Pit", "Cuboid", true, true, "world", null, null, null, null, null, null,
                0, 0, 0, maxX, 127, maxZ);
    }

    private static SurveyDue due(AreaSpec area, String trigger, List<AreaSpec> overlaps) {
        return new SurveyDue(area, 3, "Quarry", PROFILE, 4, trigger, overlaps);
    }

    private void run(SurveyRunner runner, int maxTicks) {
        for (tick = 0; tick < maxTicks && !runner.idle(); tick++) {
            runner.tick();
        }
    }

    @Test
    void aSurveyCapturesWithinTheTickBudget_countsAndReports() {
        world.loaded.addAll(List.of(key(0, 0), key(0, 1), key(1, 0), key(1, 1)));
        SurveyRunner runner = runner(settings(2, 10_000_000, false));
        assertTrue(runner.enqueue(due(cuboid(12, 4, 31, 31), "Scheduled", List.of())));
        assertFalse(runner.enqueue(due(cuboid(12, 4, 31, 31), "Scheduled", List.of())), "queued once");

        run(runner, 20);

        assertEquals(List.of("0,0@0", "0,1@0", "1,0@1", "1,1@1"), world.captures, "two chunks per tick");
        assertEquals(List.of(12), api.areas);
        ProductionSurveyApi.Report report = api.reports.get(0);
        assertEquals(List.of(new SurveyUnit("BLOCK:STONE", 1024, 1, null), new SurveyUnit("ENTITY:SHEEP", 1, 1, null)),
                report.units());
        assertEquals(4, report.profileVersion());
        assertNull(report.trigger(), "the API's pending request or Scheduled");
        assertEquals(64, report.contentHash().length());
        assertTrue(report.note().startsWith("plugin survey: blocks 131072"));
        assertEquals(SurveyRunner.Status.REPORTED, outcomes.get(0).status());
        assertEquals(1, runner.surveysReported());
    }

    @Test
    void aScheduledSurveyWaitsForLoadedChunks_aRequestedOneLoadsThem() {
        SurveyRunner runner = runner(settings(4, 10_000_000, false));
        runner.enqueue(due(cuboid(12, 4, 31, 31), "Scheduled", List.of()));
        run(runner, 5);
        assertEquals(SurveyRunner.Status.SKIPPED, outcomes.get(0).status());
        assertTrue(outcomes.get(0).detail().contains("4 of 4 chunks not loaded"));
        assertEquals(0, world.loads);
        assertTrue(api.areas.isEmpty());

        runner.enqueue(due(cuboid(12, 4, 31, 31), "Admin", List.of()));
        run(runner, 10);
        assertEquals(4, world.loads);
        assertEquals(SurveyRunner.Status.REPORTED, outcomes.get(1).status());
    }

    @Test
    void scheduledLoadsChunksLetsTheDailySurveyLoadThem_andUngeneratedChunksAreLeftOut() {
        SurveyRunner runner = runner(settings(8, 10_000_000, true));
        runner.enqueue(due(cuboid(12, 4, 79, 15), "Scheduled", List.of())); // chunks 0..4 × 0; chunk 4 never generated
        run(runner, 10);
        assertEquals(5, world.loads);
        assertEquals(new SurveyUnit("BLOCK:STONE", 1024, 1, null), api.reports.get(0).units().get(0));
        assertTrue(api.reports.get(0).note().contains("columns not loaded 256"));
        assertTrue(api.reports.get(0).note().contains("chunks never generated 1"));
    }

    @Test
    void anAreaOverTheCapIsSkipped() {
        SurveyRunner runner = runner(settings(2, 1000, true));
        runner.enqueue(due(cuboid(12, 4, 31, 31), "Admin", List.of()));
        run(runner, 3);
        assertEquals(SurveyRunner.Status.SKIPPED, outcomes.get(0).status());
        assertTrue(outcomes.get(0).detail().contains("max-area-blocks"));
        assertTrue(world.captures.isEmpty());
    }

    @Test
    void afterANotableChangeTheReportSaysSo_andOverlapsSplitTheUnits() {
        world.loaded.add(key(0, 0));
        SurveyRunner runner = runner(settings(4, 10_000_000, false));
        runner.markNotable(12);
        AreaSpec otherLogger = new AreaSpec(30, 8, "Other", "Edge", "Region", false, true, "world", "pen", null, null, null,
                null, null, null, null, null, null, null, null);
        runner.enqueue(due(cuboid(12, 4, 15, 15), "Admin", List.of(otherLogger)));
        run(runner, 5);
        ProductionSurveyApi.Report report = api.reports.get(0);
        assertEquals("NotableChange", report.trigger());
        assertEquals(List.of(new SurveyUnit("BLOCK:STONE", 240, 1, null), new SurveyUnit("BLOCK:STONE", 16, 2, null),
                new SurveyUnit("ENTITY:SHEEP", 1, 2, null)), report.units());
    }

    @Test
    void refusalsEndTheSurveyWithTheReason() {
        world.loaded.add(key(0, 0));
        SurveyRunner runner = runner(settings(4, 10_000_000, false));
        api.refuse = new ApiException("http://api/x", 409, "conflict", "{\"error\":\"Production is off\"}");
        runner.enqueue(due(cuboid(12, 4, 15, 15), "Admin", List.of()));
        AreaSpec missing = new AreaSpec(13, 4, "Quarry", "Gone", "Region", true, true, "world", "gone", null, null, null,
                null, null, null, null, null, null, null, null);
        runner.enqueue(due(missing, "Admin", List.of()));
        runner.enqueue(due(new AreaSpec(14, 4, null, null, "Cuboid", true, true, "nether", null, null, null, null, null, null,
                0, 0, 0, 1, 1, 1), "Admin", List.of()));
        run(runner, 10);
        assertEquals("API 409: {\"error\":\"Production is off\"}", outcomes.get(0).detail());
        assertEquals(SurveyRunner.Status.FAILED, outcomes.get(0).status());
        assertEquals("WorldGuard region gone not found in world", outcomes.get(1).detail());
        assertEquals(SurveyRunner.Status.SKIPPED, outcomes.get(2).status());
        assertTrue(runner.idle());
        assertEquals(3, runner.recent().size());
    }
}
