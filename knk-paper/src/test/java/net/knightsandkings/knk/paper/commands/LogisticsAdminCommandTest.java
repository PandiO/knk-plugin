package net.knightsandkings.knk.paper.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.TransportLanesApi;
import net.knightsandkings.knk.core.production.transport.LaneDue;
import net.knightsandkings.knk.core.production.transport.LaneMeasurement;
import net.knightsandkings.knk.core.production.transport.LanePosition;
import net.knightsandkings.knk.core.production.transport.LaneSettings;
import net.knightsandkings.knk.core.production.transport.RoadConnection;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.RouterParameters;
import net.knightsandkings.knk.paper.production.LaneModule;

/** {@code /knk logistics lanes measure|status} (PandiO/knk-workspace#681); the node is the metadata's. */
class LogisticsAdminCommandTest {

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock staff;
    private final AtomicReference<LaneModule> module = new AtomicReference<>();
    private final List<List<LaneMeasurement>> reports = new ArrayList<>();
    private final List<String> connections = new ArrayList<>();
    private LogisticsAdminCommand command;

    /** No road network is loaded in this test: a pair in another world is measured "unreachable", one without a
     * position is skipped. Structure 9 is a warehouse (404). */
    private final TransportLanesApi api = new TransportLanesApi() {
        public CompletableFuture<List<LaneDue>> due(int limit) {
            return CompletableFuture.completedFuture(List.of(
                    new LaneDue(5, 9, "Bare quarry", "Docks", new LanePosition("world", 0, 65, 0),
                            new LanePosition("world_nether", 0, 65, 0), "Missing", null),
                    new LaneDue(6, 9, "Lost mine", "Docks", null, new LanePosition("world", 0, 65, 0), "Missing", null)));
        }

        public CompletableFuture<ReportResult> report(List<LaneMeasurement> lanes) {
            reports.add(lanes);
            return CompletableFuture.completedFuture(new ReportResult(lanes.size(), 0));
        }

        public CompletableFuture<Void> setRoadConnection(int structureId, RoadConnection state) {
            if (structureId == 9) {
                return CompletableFuture.failedFuture(new CompletionException(new ApiException("u", 404, "x", "")));
            }
            connections.add(structureId + "=" + state);
            return CompletableFuture.completedFuture(null);
        }
    };

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        staff = server.addPlayer();
        plugin = MockBukkit.createMockPlugin();
        command = new LogisticsAdminCommand(module::get, plugin);
    }

    @AfterEach
    void tearDown() {
        if (module.get() != null) {
            module.get().stop();
        }
        MockBukkit.unmock();
    }

    private String drain(PlayerMock player) {
        StringBuilder all = new StringBuilder();
        String line;
        while ((line = player.nextMessage()) != null) {
            all.append(org.bukkit.ChatColor.stripColor(line)).append('\n');
        }
        return all.toString();
    }

    @Test
    void metadataGatesOnTheProductionNode() {
        assertEquals("logistics", LogisticsAdminCommand.metadata().name());
        assertEquals("knk.admin.world.production", LogisticsAdminCommand.metadata().permission());
    }

    @Test
    void offWithoutProductionEnabled() {
        command.execute(staff, new String[] {"lanes", "measure"});
        assertTrue(drain(staff).contains("Production is off on this server (production.enabled: false)."));
    }

    @Test
    void measureRunsNow_reportsTheLanes_andTellsTheSenderTheResult_A13() throws Exception {
        LaneModule lanes = new LaneModule(plugin, LaneSettings.defaults(true), api,
                world -> RoadNetworkSnapshot.empty(world), RouterParameters::defaults);
        module.set(lanes);

        command.execute(staff, new String[] {"lanes", "measure"});
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); // the run finishes on the KnK-lanes thread
        while (lanes.job().last() == null && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        server.getScheduler().performTicks(2);

        String said = drain(staff);
        assertTrue(said.contains("Reading the lane list and measuring over the road network"), said);
        assertTrue(said.contains("2 due, 0 reachable, 1 without a road, 1 skipped"), said);
        assertTrue(said.contains("#6 Lost mine → #9 Docks: skipped, #6 has no location"), said);
        assertTrue(said.contains("Structure #5 is now Unconnected: its automatic transport is held"), said);
        assertEquals(1, reports.size());
        assertEquals(5, reports.get(0).get(0).fromStructureId());
        assertEquals(List.of("5=Unconnected"), connections);

        command.execute(staff, new String[] {"lanes", "status"});
        String status = drain(staff);
        assertTrue(status.contains("Lane measurer: every 60 min, 100 pairs per run"), status);
        assertTrue(status.contains("lanes known since start: 1"), status);
        assertTrue(status.contains("2 due, 0 reachable"), status);
        assertEquals(Map.of(5, RoadConnection.Unconnected), lanes.job().last().connectionsSent());
    }

    @Test
    void statusBeforeAnyRun_andTheUsageAndCompletion() {
        module.set(new LaneModule(plugin, new LaneSettings(true, 0, 100, 48, true), api, w -> null,
                RouterParameters::defaults));
        command.execute(staff, new String[] {"lanes", "status"});
        String said = drain(staff);
        assertTrue(said.contains("only on /knk logistics lanes measure"), said);
        assertTrue(said.contains("No run since the server started."), said);

        command.execute(staff, new String[] {"lanes"});
        assertTrue(drain(staff).contains("Usage: /knk logistics lanes <measure|status>"));
        assertEquals(List.of("lanes"), command.complete(staff, new String[] {""}));
        assertEquals(List.of("measure", "status"), command.complete(staff, new String[] {"lanes", ""}));
        assertEquals(List.of("status"), command.complete(staff, new String[] {"lanes", "st"}));
    }
}
