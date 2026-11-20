package net.knightsandkings.knk.core.production.transport;

import static net.knightsandkings.knk.core.production.transport.LaneFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.TransportLanesApi;

/** A measuring run (PandiO/knk-workspace#681): list, measure, report in chunks, road connections. */
class LaneJobTest {

    /** The API as the L14 contract describes it; warehouses are not production structures (404). */
    static final class FakeApi implements TransportLanesApi {
        List<LaneDue> due = new ArrayList<>();
        final List<List<LaneMeasurement>> reports = new ArrayList<>();
        final Map<Integer, RoadConnection> connections = new LinkedHashMap<>();
        Set<Integer> warehouses = Set.of(DOCKS, ISLAND_WAREHOUSE);
        RuntimeException reportFails;
        RuntimeException dueFails;
        CompletableFuture<List<LaneDue>> pendingDue;
        int dueCalls;

        @Override
        public CompletableFuture<List<LaneDue>> due(int limit) {
            dueCalls++;
            if (dueFails != null) {
                return CompletableFuture.failedFuture(dueFails);
            }
            if (pendingDue != null) {
                return pendingDue;
            }
            return CompletableFuture.completedFuture(due.subList(0, Math.min(limit, due.size())));
        }

        @Override
        public CompletableFuture<ReportResult> report(List<LaneMeasurement> lanes) {
            if (reportFails != null) {
                return CompletableFuture.failedFuture(new CompletionException(reportFails));
            }
            reports.add(List.copyOf(lanes));
            return CompletableFuture.completedFuture(new ReportResult(lanes.size(), 0));
        }

        @Override
        public CompletableFuture<Void> setRoadConnection(int structureId, RoadConnection state) {
            if (warehouses.contains(structureId)) {
                return CompletableFuture.failedFuture(new CompletionException(
                        new ApiException("url", 404, "Not Found", "")));
            }
            connections.put(structureId, state);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static LaneJob job(FakeApi api, LaneSettings settings) {
        return new LaneJob(api, measurer(), new RoadConnectionBook(), settings, Runnable::run, null, CLOCK);
    }

    private static LaneSettings settings() {
        return LaneSettings.defaults(true);
    }

    @Test
    void aRun_reportsEveryMeasuredPair_skipsTheUnmeasurable_andSetsTheProducersRoadConnection() {
        FakeApi api = new FakeApi();
        int bareQuarry = 5;
        int noLocation = 6;
        api.due.add(due(LOGGER, DOCKS, LOGGER_AT, DOCKS_AT));
        api.due.add(due(bareQuarry, DOCKS, FAR_AT, DOCKS_AT));
        api.due.add(due(noLocation, DOCKS, null, DOCKS_AT));

        LaneJob.Summary summary = job(api, settings()).run("test").join();

        assertNull(summary.error());
        assertEquals(3, summary.due());
        assertEquals(1, summary.reachable());
        assertEquals(1, summary.unreachable());
        assertEquals(1, summary.skipped());
        assertEquals(2, summary.created());
        assertEquals(1, api.reports.size());
        assertEquals(List.of(LOGGER, bareQuarry), api.reports.get(0).stream().map(LaneMeasurement::fromStructureId).toList(),
                "the pair without a location is not reported and stays due");
        assertEquals(Map.of(LOGGER, RoadConnection.Connected, bareQuarry, RoadConnection.Unconnected), api.connections,
                "A13: the quarry without a road is Unconnected; the warehouse's 404 is ignored");
        assertEquals(Map.of(LOGGER, RoadConnection.Connected, bareQuarry, RoadConnection.Unconnected),
                summary.connectionsSent());
        assertTrue(summary.notes().stream().anyMatch(n -> n.contains("220.0 blocks by road")), summary.notes().toString());
    }

    @Test
    void anUnchangedConnection_isNotSentAgain_andAWarehouseIsAskedOnlyOnce() {
        FakeApi api = new FakeApi();
        api.due.add(due(LOGGER, DOCKS, LOGGER_AT, DOCKS_AT));
        LaneJob job = job(api, settings());
        job.run("first").join();
        api.connections.clear();

        LaneJob.Summary second = job.run("second").join();

        assertEquals(Map.of(), api.connections);
        assertEquals(Map.of(), second.connectionsSent());
        assertEquals(Set.of(DOCKS), job.book().notProducers());
    }

    @Test
    void withRoadConnectionsOff_onlyLanesAreReported() {
        FakeApi api = new FakeApi();
        api.due.add(due(LOGGER, DOCKS, LOGGER_AT, DOCKS_AT));
        LaneSettings off = new LaneSettings(true, 60, 100, 48, false);

        job(api, off).run("test").join();

        assertEquals(1, api.reports.size());
        assertEquals(Map.of(), api.connections);
    }

    @Test
    void moreThan500Lanes_areReportedInChunks_andTheBatchIsTheLimit() {
        FakeApi api = new FakeApi();
        for (int i = 0; i < 700; i++) {
            api.due.add(due(1000 + i, DOCKS, LOGGER_AT, new LanePosition("elsewhere", 0, 65, 0)));
        }
        LaneSettings big = new LaneSettings(true, 60, 650, 48, false);

        LaneJob.Summary summary = job(api, big).run("test").join();

        assertEquals(650, summary.due());
        assertEquals(List.of(500, 150), api.reports.stream().map(List::size).toList());
        assertEquals(650, summary.created());
    }

    @Test
    void aFailedReport_setsNoConnection_andTheSummaryCarriesTheError() {
        FakeApi api = new FakeApi();
        api.due.add(due(LOGGER, DOCKS, LOGGER_AT, DOCKS_AT));
        api.reportFails = new ApiException("url", 503, "Service Unavailable", "");

        LaneJob.Summary summary = job(api, settings()).run("test").join();

        assertTrue(summary.error().contains("report failed: HTTP 503"), summary.error());
        assertEquals(Map.of(), api.connections);
        assertEquals(0, summary.created());
    }

    @Test
    void aFailedLaneList_isASummaryWithTheError_neverAnException() {
        FakeApi api = new FakeApi();
        api.dueFails = new IllegalStateException("API down");

        LaneJob job = job(api, settings());
        LaneJob.Summary summary = job.run("test").join();

        assertTrue(summary.error().contains("API down"));
        assertTrue(summary.line().startsWith("lane list not read"));
        assertSame(summary, job.last());
    }

    @Test
    void oneRunAtATime_aSecondCallGetsTheRunningOne() {
        FakeApi api = new FakeApi();
        api.pendingDue = new CompletableFuture<>();
        LaneJob job = job(api, settings());

        CompletableFuture<LaneJob.Summary> first = job.run("first");
        CompletableFuture<LaneJob.Summary> second = job.run("second");

        assertTrue(job.isRunning());
        assertSame(first, second);
        assertEquals(1, api.dueCalls);
        api.pendingDue.complete(List.of());
        assertEquals(0, first.join().due());
        assertFalse(job.isRunning());
    }
}
