package net.knightsandkings.knk.paper.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.ports.api.ProductionSurveyApi;
import net.knightsandkings.knk.core.production.survey.AreaSpec;
import net.knightsandkings.knk.core.production.survey.SurveyDue;
import net.knightsandkings.knk.core.production.survey.SurveySettings;
import net.knightsandkings.knk.paper.production.AreaOutlineRenderer;
import net.knightsandkings.knk.paper.production.SurveyModule;

/** {@code /knk production survey|survey status|area show} (PandiO/knk-workspace#679); the node is the metadata's. */
class ProductionAdminCommandTest {

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock staff;
    private final AtomicReference<SurveyModule> module = new AtomicReference<>();
    private final List<String> requests = new ArrayList<>();
    private ProductionAdminCommand command;

    private final ProductionSurveyApi api = new ProductionSurveyApi() {
        public CompletableFuture<List<SurveyDue>> surveyDue(int limit) {
            return CompletableFuture.completedFuture(List.of());
        }

        public CompletableFuture<ReportResult> report(int areaId, Report report) {
            throw new AssertionError("no survey runs here");
        }

        public CompletableFuture<List<AreaSpec>> requestSurvey(int structureId, Integer areaId) {
            requests.add(structureId + "/" + areaId);
            return CompletableFuture.completedFuture(List.of(area(5, structureId)));
        }

        public CompletableFuture<List<AreaSpec>> areas() {
            return CompletableFuture.completedFuture(List.of(area(5, 12), area(6, 12)));
        }
    };

    private static AreaSpec area(int id, int structure) {
        return new AreaSpec(id, structure, "Quarry", "Pit " + id, "Cuboid", true, true, "world", null, null, null, null,
                null, null, 0, 60, 0, 9, 70, 9);
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
        staff = server.addPlayer();
        plugin = MockBukkit.createMockPlugin();
        command = new ProductionAdminCommand(module::get, new AreaOutlineRenderer(plugin), plugin);
    }

    @AfterEach
    void tearDown() {
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
        assertEquals("production", ProductionAdminCommand.metadata().name());
        assertEquals("knk.admin.world.production", ProductionAdminCommand.metadata().permission());
    }

    @Test
    void offWithoutProductionEnabled() {
        command.execute(staff, new String[] {"survey", "12"});
        assertTrue(drain(staff).contains("Production is off on this server (production.enabled: false)."));
    }

    @Test
    void surveyAsksTheApiForTheStructure_andStatusShowsTheQueue() {
        module.set(new SurveyModule(plugin, SurveySettings.defaults(true), api));
        command.execute(staff, new String[] {"survey", "12", "5"});
        server.getScheduler().performTicks(2);
        assertEquals(List.of("12/5"), requests);
        String said = drain(staff);
        assertTrue(said.contains("Asking the API to survey structure #12 area #5"), said);
        assertTrue(said.contains("Queued 1 area(s)"), said);

        command.execute(staff, new String[] {"survey", "status"});
        assertTrue(drain(staff).contains("Surveys: 0 queued, running: none"));
    }

    @Test
    void wrongArgumentsGetTheUsage_andAShowDrawsTheStructuresAreas() {
        module.set(new SurveyModule(plugin, SurveySettings.defaults(true), api));
        command.execute(staff, new String[] {"area"});
        assertTrue(drain(staff).contains("Usage: /knk production"));
        command.execute(staff, new String[] {"survey", "nope"});
        assertTrue(drain(staff).contains("Unknown structure 'nope'"));

        command.execute(staff, new String[] {"area", "show", "12", "6", "5"});
        server.getScheduler().performTicks(2);
        String said = drain(staff);
        assertTrue(said.contains("Area #6 \"Pit 6\" of structure #12 Quarry: Cuboid, exclusive, x 0..9, y 60..70, z 0..9"), said);
        assertTrue(said.contains("Drawing 1 outline(s) for 5 s."), said);
        assertEquals(List.of("survey", "area", "harvest"), command.complete(staff, new String[] {""}));
        assertEquals(List.of("status", "12"), command.complete(staff, new String[] {"survey", ""}));
        assertEquals(List.of("status", "refresh"), command.complete(staff, new String[] {"harvest", ""}));
    }

    @Test
    void harvestStatusWithoutTheHarvestModuleSaysItIsNotRunning_134() {
        module.set(new SurveyModule(plugin, SurveySettings.defaults(true), api));
        command.execute(staff, new String[] {"harvest", "status"});
        assertTrue(drain(staff).contains("Player harvesting is not running on this server."));
    }
}
