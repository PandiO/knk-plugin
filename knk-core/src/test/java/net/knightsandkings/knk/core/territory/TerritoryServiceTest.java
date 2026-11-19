package net.knightsandkings.knk.core.territory;

import static net.knightsandkings.knk.core.territory.TerritoryFixtures.WORLD;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.cuboid;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.district;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.map.RegionOutline;
import net.knightsandkings.knk.core.ports.api.TerritoryApi;

/** The rebuild, skip, report and outline flow of the game server's territory (PandiO/knk-workspace#689). */
class TerritoryServiceTest {

    private final Map<String, RegionOutline> regions = new HashMap<>();
    private final List<TerritoryReport> reports = new ArrayList<>();
    private final List<Map<String, Object>> sentOutlines = new ArrayList<>();
    private TerritoryInputs inputs = withVersion("v1", true, List.of(district(10, "d", 1, 40), district(11, "gone", 1, 40)));
    private int reportStatus = 200;
    private List<TerritoryService.StructureRef> structureRefs = List.of(
            new TerritoryService.StructureRef(100, 10, WORLD, "s-inside"),
            new TerritoryService.StructureRef(101, 10, WORLD, "s-far"),
            new TerritoryService.StructureRef(102, 99, WORLD, "s-far"));

    private final TerritoryApi api = new TerritoryApi() {
        @Override
        public CompletableFuture<TerritoryInputs> fetchInputs() {
            return CompletableFuture.completedFuture(inputs);
        }

        @Override
        public CompletableFuture<TerritoryReport.Result> report(TerritoryReport report) {
            if (reportStatus != 200) {
                return CompletableFuture.failedFuture(new CompletionException(
                        new ApiException("http://api/Territory/report", reportStatus, "Conflict", "{}")));
            }
            reports.add(report);
            return CompletableFuture.completedFuture(new TerritoryReport.Result(report.world(), 1, 0, 0, 0, 0, List.of()));
        }
    };

    private static TerritoryInputs withVersion(String version, boolean productionEnabled, List<TerritoryInputs.Source> sources) {
        return new TerritoryInputs(productionEnabled, version, Map.of(1, "Phoenix"), Map.of(), sources, List.of());
    }

    private TerritoryService service(boolean mapOutlines) {
        TerritorySettings settings = new TerritorySettings(true, 10, true, true, mapOutlines, 0, Map.of());
        return new TerritoryService(settings, api,
                (world, regionId) -> Optional.ofNullable(regions.get(regionId)),
                () -> CompletableFuture.completedFuture(structureRefs),
                (method, argument) -> {
                    assertEquals(TerritoryService.OUTLINES_METHOD, method);
                    return sentOutlines.add(argument);
                },
                Runnable::run, Runnable::run, Logger.getAnonymousLogger());
    }

    {
        regions.put("d", cuboid("d", 0, 0, 15, 15));
        regions.put("s-inside", cuboid("s-inside", 2, 2, 4, 4));
        regions.put("s-far", cuboid("s-far", 500, 500, 504, 504));
    }

    @Test
    void theFirstRefresh_buildsTheCache_warnsAboutAMissingRegion_andReports() {
        TerritoryService service = service(false);
        List<TerritoryCache> seen = new ArrayList<>();
        service.addListener(seen::add);

        TerritoryService.Outcome outcome = service.refresh("test", false).join();

        assertTrue(outcome.rebuilt());
        assertEquals(1, seen.size());
        assertEquals(TerritoryHolder.town(1), service.lookup(WORLD, 50, 8).owner());
        assertTrue(service.cache().warnings().get(0).contains("region gone not found"));
        assertEquals(1, reports.size());
        // structure 101 stands in the wilderness: flagged; 100 is home; 102's district is unknown: not reported
        assertEquals(1, reports.get(0).structures().size());
        assertEquals(101, reports.get(0).structures().get(0).structureId());
        assertEquals(TerritoryReport.StructureFlag.OUTSIDE, reports.get(0).structures().get(0).flag());
        assertTrue(service.lastReport().startsWith(WORLD + ": 0 overlap(s), 1 flagged structure(s)"));
    }

    /** P2 (PandiO/knk-workspace#91, #677): the structure search's townId places rural structures (no district) too. */
    @Test
    void ruralStructures_areFlaggedThroughTheirTownId_fromTheStructureSearch() {
        structureRefs = List.of(
                new TerritoryService.StructureRef(200, null, 1, WORLD, "s-inside"), // rural Phoenix, home: no flag
                new TerritoryService.StructureRef(201, null, 1, WORLD, "s-far"), // rural Phoenix, wilderness: outside
                new TerritoryService.StructureRef(202, null, 2, WORLD, "s-inside"), // rural Rivertown on Phoenix land
                new TerritoryService.StructureRef(203, null, null, WORLD, "s-far"), // no town known: left out
                new TerritoryService.StructureRef(204, 99, 1, WORLD, "s-inside")); // townId wins over an unknown district
        TerritoryService service = service(false);

        service.refresh("test", false).join();

        List<Integer> flagged = reports.get(0).structures().stream().map(TerritoryReport.StructureFlag::structureId).toList();
        assertEquals(List.of(201, 202), flagged);
        assertTrue(reports.get(0).structures().stream()
                .allMatch(entry -> TerritoryReport.StructureFlag.OUTSIDE.equals(entry.flag())));
    }

    @Test
    void unchangedInputsAndShapes_skipTheRebuild_aNewVersionOrShapeOrForceDoesNot() {
        TerritoryService service = service(false);
        service.refresh("first", false).join();

        assertFalse(service.refresh("again", false).join().rebuilt());
        assertEquals(1, reports.size(), "nothing to report again");

        regions.put("d", cuboid("d", 0, 0, 31, 15));
        assertTrue(service.refresh("shape", false).join().rebuilt());

        inputs = withVersion("v2", true, inputs.sources());
        assertTrue(service.refresh("version", false).join().rebuilt());

        assertTrue(service.refresh("forced", true).join().rebuilt());
        assertEquals(4, reports.size());
    }

    @Test
    void withTheApiFlagOff_nothingIsReported() {
        inputs = withVersion("v1", false, inputs.sources());
        TerritoryService service = service(false);

        service.refresh("test", false).join();

        assertTrue(reports.isEmpty());
        assertTrue(service.lastReport().contains("Production:Enabled is off"));
    }

    @Test
    void aRefusedReport_isLoggedAndNamed_notThrown() {
        reportStatus = 409;
        TerritoryService service = service(false);

        assertTrue(service.refresh("test", false).join().rebuilt());
        assertTrue(service.lastReport().contains("refused, the API's Production:Enabled is off"), service.lastReport());
    }

    @Test
    void mapOutlines_areSentPerWorld_onlyWhenSwitchedOn() {
        service(false).refresh("off", false).join();
        assertTrue(sentOutlines.isEmpty());

        service(true).refresh("on", false).join();
        assertEquals(1, sentOutlines.size());
        assertEquals(WORLD, sentOutlines.get(0).get("world"));
        assertEquals("v1", sentOutlines.get(0).get("inputsVersion"));
        List<?> outlines = (List<?>) sentOutlines.get(0).get("outlines");
        assertEquals(1, outlines.size());
        assertEquals("Owned", ((Map<?, ?>) outlines.get(0)).get("kind"));
    }

    @Test
    void aFailedInputsRead_failsTheRefresh_andKeepsTheOldCache() {
        TerritoryService service = service(false);
        service.refresh("first", false).join();
        TerritoryCache before = service.cache();
        TerritoryApi broken = new TerritoryApi() {
            @Override
            public CompletableFuture<TerritoryInputs> fetchInputs() {
                return CompletableFuture.failedFuture(new IllegalStateException("API down"));
            }

            @Override
            public CompletableFuture<TerritoryReport.Result> report(TerritoryReport report) {
                throw new AssertionError();
            }
        };
        TerritoryService down = new TerritoryService(service.settings(), broken, (w, r) -> Optional.empty(), null, null,
                Runnable::run, Runnable::run, Logger.getAnonymousLogger());

        assertTrue(down.refresh("down", false).handle((ok, error) -> error != null).join());
        assertEquals(TerritoryCache.EMPTY, down.cache());
        assertEquals(before, service.cache());
        assertTrue(down.refresh("again", false).isCompletedExceptionally(), "a new refresh runs after a failed one");
    }
}
