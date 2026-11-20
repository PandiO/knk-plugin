package net.knightsandkings.knk.paper.scenario.scenarios;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Sheep;

import net.knightsandkings.knk.core.ports.api.ProductionSurveyApi;
import net.knightsandkings.knk.core.production.survey.AreaShape;
import net.knightsandkings.knk.core.production.survey.AreaSpec;
import net.knightsandkings.knk.core.production.survey.PlaceholderOverlay;
import net.knightsandkings.knk.core.production.survey.SurveyDue;
import net.knightsandkings.knk.core.production.survey.SurveySettings;
import net.knightsandkings.knk.core.production.survey.SurveyUnit;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.production.SurveyRunner;
import net.knightsandkings.knk.paper.production.SurveyWorld;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * P4b (PandiO/knk-workspace#679, smoke § 5.2): a yield-area survey of real blocks. The scene gets a stone floor with a
 * coal ore, an oak tree, a wheat row on farmland, a water pool and two sheep; the plugin's survey runner captures the
 * chunk snapshots on the main thread (two per tick), counts them on its own thread and reports to a recording API. A
 * shared area of another structure around the tree makes the tree's {@code sharedBy} 2 (G7).
 */
public final class ProductionAreaSurveyScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "production-area-survey";
    }

    @Override
    public String description() {
        return "A survey of a production area counts rock, ore, a tree, crops, water and sheep from chunk snapshots off the main thread";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        List<ProductionSurveyApi.Report> reports = new ArrayList<>();
        List<SurveyRunner.Outcome> outcomes = new ArrayList<>();
        AtomicReference<SurveyRunner> runner = new AtomicReference<>();
        AtomicReference<String> countingThread = new AtomicReference<>();
        ExecutorService counting = Executors.newSingleThreadExecutor(r -> new Thread(r, "KnK-survey-scenario"));

        plan.act("build: stone floor, coal ore, oak tree, wheat on farmland, water pool, two sheep", () -> {
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        scene.block(x, 0, z).setType(Material.STONE, false);
                    }
                }
                scene.block(2, 0, 2).setType(Material.COAL_ORE, false);
                for (int y = 1; y <= 5; y++) {
                    scene.block(10, y, 10).setType(Material.OAK_LOG, false);
                }
                for (int x = 9; x <= 11; x++) {
                    for (int z = 9; z <= 11; z++) {
                        scene.block(x, 6, z).setType(Material.OAK_LEAVES, false);
                    }
                }
                for (int z = 20; z <= 22; z++) {
                    scene.block(20, 0, z).setType(Material.FARMLAND, false);
                    scene.block(20, 1, z).setType(Material.WHEAT, false);
                }
                for (int x = 25; x <= 27; x++) {
                    for (int z = 25; z <= 27; z++) {
                        scene.block(x, 0, z).setType(Material.WATER, false);
                    }
                }
                for (int i = 0; i < 2; i++) {
                    Sheep sheep = scene.spawn(15.5 + i, 1, 15.5, Sheep.class);
                    sheep.setAI(false);
                }
            })
            .cleanup("stop the counting thread", counting::shutdownNow)
            .act("queue a survey of the scene as a Hybrid quarry-farm area, with a logger's radius around the tree", () -> {
                ProductionSurveyApi api = new ProductionSurveyApi() {
                    public CompletableFuture<List<SurveyDue>> surveyDue(int limit) {
                        return CompletableFuture.completedFuture(List.of());
                    }

                    public CompletableFuture<ReportResult> report(int areaId, Report report) {
                        countingThread.set(Thread.currentThread().getName());
                        reports.add(report);
                        return CompletableFuture.completedFuture(new ReportResult(1, 0, 0, 0));
                    }

                    public CompletableFuture<List<AreaSpec>> requestSurvey(int structureId, Integer areaId) {
                        return CompletableFuture.completedFuture(List.of());
                    }

                    public CompletableFuture<List<AreaSpec>> areas() {
                        return CompletableFuture.completedFuture(List.of());
                    }
                };
                SurveyRunner surveys = new SurveyRunner(SurveySettings.defaults(true), api,
                        name -> Bukkit.getWorld(name) == null ? null : SurveyWorld.of(Bukkit.getWorld(name)),
                        (world, id) -> java.util.Optional.empty(), PlaceholderOverlay.NONE, counting,
                        task -> Bukkit.getScheduler().runTask(scene.services().plugin(), task),
                        Logger.getLogger("KnK scenario"), Clock.systemUTC());
                surveys.addListener(outcomes::add);
                runner.set(surveys);
                String world = scene.world().getName();
                AreaSpec area = new AreaSpec(scene.fakeId(1), scene.fakeId(2), "Scenario farm", "Scene", "Cuboid", true, true,
                        world, null, null, null, null, null, null, scene.worldX(0), scene.worldY(0), scene.worldZ(0),
                        scene.worldX(31), scene.worldY(23), scene.worldZ(31));
                AreaSpec logger = new AreaSpec(scene.fakeId(3), scene.fakeId(4), "Scenario logger", "Forest", "Radius", false,
                        true, world, null, null, scene.worldX(10), scene.worldY(1), scene.worldZ(10), 3, null, null, null, null,
                        null, null);
                surveys.enqueue(new SurveyDue(area, 1, "Scenario kind",
                        "{\"unitTypes\":[\"BLOCK\",\"TREE\",\"SAPLING\",\"CROP\",\"WATER\",\"ENTITY\"]}", 7, "Admin",
                        List.of(logger)));
            })
            .await("the survey is reported", 200, () -> {
                runner.get().tick();
                return !outcomes.isEmpty();
            })
            .check("it was reported, counted off the main thread, two chunks per tick at most", () -> {
                SurveyRunner.Outcome outcome = outcomes.get(0);
                if (outcome.status() != SurveyRunner.Status.REPORTED) {
                    return outcome.summary();
                }
                if (!"KnK-survey-scenario".equals(countingThread.get())) {
                    return "reported from " + countingThread.get();
                }
                long chunks = new AreaShape.Cuboid(scene.worldX(0), 0, scene.worldZ(0), scene.worldX(31), 0, scene.worldZ(31)).chunkCount();
                return runner.get().chunksCaptured() == chunks ? null : runner.get().chunksCaptured() + " chunks captured of " + chunks;
            })
            .check("the units: stone, coal, a small oak shared by 2, wheat, water, sheep", () -> {
                ProductionSurveyApi.Report report = reports.get(0);
                Map<String, Long> totals = report.units().stream()
                        .collect(Collectors.groupingBy(u -> u.unitKey().startsWith("WATER:") ? "WATER" : u.unitKey(),
                                Collectors.summingLong(SurveyUnit::count)));
                Map<String, Long> expected = Map.of("BLOCK:STONE", 1011L, "BLOCK:COAL_ORE", 1L, "TREE:OAK:SMALL", 1L,
                        "CROP:WHEAT", 3L, "WATER", 9L, "ENTITY:SHEEP", 2L);
                if (!totals.equals(expected)) {
                    return "units " + report.units();
                }
                boolean shared = report.units().contains(new SurveyUnit("TREE:OAK:SMALL", 1, 2, null));
                return shared && report.profileVersion() == 7 && report.contentHash().length() == 64 ? null
                        : "tree not shared by 2 or report fields wrong: " + report;
            });
    }
}
