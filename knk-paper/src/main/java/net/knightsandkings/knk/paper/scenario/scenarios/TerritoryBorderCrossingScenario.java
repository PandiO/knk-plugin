package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.bukkit.event.HandlerList;

import net.knightsandkings.knk.core.ports.api.TerritoryApi;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.core.territory.TerritoryHolder;
import net.knightsandkings.knk.core.territory.TerritoryInputs;
import net.knightsandkings.knk.core.territory.TerritoryReport;
import net.knightsandkings.knk.core.territory.TerritoryService;
import net.knightsandkings.knk.core.territory.TerritorySettings;
import net.knightsandkings.knk.core.territory.TerritoryTracker;
import net.knightsandkings.knk.paper.map.RegionOutlineAdapter;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.territory.PlayerTerritoryChangeEvent;
import net.knightsandkings.knk.paper.territory.TerritoryListener;

/**
 * Territory (PandiO/knk-workspace#689, smoke § 1.3 steps 2 and 3): a district's real WorldGuard region with a range of 6
 * blocks, the plugin's own cache built from fake API inputs, and a scripted player stepping across the border. Moving
 * within the territory changes nothing; one block past the range the player gets the exit line and one
 * {@link PlayerTerritoryChangeEvent}; one block back, the entry line. The border is measured from the region's nearest
 * edge (G11), per block column (the border runs through a split chunk).
 */
public final class TerritoryBorderCrossingScenario implements Scenario<ScenarioScene> {

    static final String TOWN = "ScnTerritoryTown";

    @Override
    public String name() {
        return "territory-border-crossing";
    }

    @Override
    public String description() {
        return "A player crossing a district's territory border (range from the region edge) gets the exit and entry lines";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        String regionId = scene.uniqueName("terr");
        int townId = 421;
        AtomicReference<TerritoryService> service = new AtomicReference<>();
        AtomicReference<TerritoryListener> listener = new AtomicReference<>();
        AtomicInteger changes = new AtomicInteger();
        ScenarioPlayer walker = scene.player("ScnBorderWalker", p -> p.account(scene.fakeId(1)));

        plan.act("draw the district's region (scene x and z 2 to 5)",
                () -> ScenarioRegions.cuboid(scene, regionId, 2, 0, 2, 5, 10, 5))
            .cleanup("remove the region", () -> ScenarioRegions.removeAll(scene, regionId))
            .act("build the territory cache from fake inputs: range 6 from the region's edge", () -> {
                TerritoryInputs.Source district = new TerritoryInputs.Source(TerritoryInputs.SourceType.DISTRICT,
                        scene.fakeId(2), "Scenario district", scene.world().getName(), regionId, List.of(),
                        TerritoryHolder.town(townId), false, 6);
                TerritoryInputs inputs = new TerritoryInputs(false, "scenario", Map.of(townId, TOWN), Map.of(),
                        List.of(district), List.of());
                TerritoryApi api = new TerritoryApi() {
                    @Override
                    public CompletableFuture<TerritoryInputs> fetchInputs() {
                        return CompletableFuture.completedFuture(inputs);
                    }

                    @Override
                    public CompletableFuture<TerritoryReport.Result> report(TerritoryReport report) {
                        return CompletableFuture.failedFuture(new IllegalStateException("no report in a scenario"));
                    }
                };
                TerritorySettings settings = new TerritorySettings(true, 0, true, false, false, 0, Map.of());
                service.set(new TerritoryService(settings, api,
                        new RegionOutlineAdapter(() -> List.of(ScenarioRegions.manager(scene))), null, null,
                        Runnable::run, Runnable::run, Logger.getLogger("KnK scenario")));
                TerritoryTracker tracker = new TerritoryTracker(() -> service.get().cache());
                listener.set(new TerritoryListener(tracker, settings, scene.services().plugin().getServer().getPluginManager()));
                scene.services().plugin().getServer().getPluginManager().registerEvents(listener.get(),
                        scene.services().plugin());
                service.get().refresh("scenario", true).join();
            })
            .cleanup("unregister the territory listener", () -> {
                if (listener.get() != null) {
                    HandlerList.unregisterAll(listener.get());
                }
            })
            .check("the border runs between scene x 11 and 12 (6 and 7 blocks from the edge at x 5)", () -> {
                var cache = service.get().cache();
                String world = scene.world().getName();
                if (!TerritoryHolder.town(townId).equals(cache.lookup(world, scene.worldX(11), scene.worldZ(3)).owner())) {
                    return "x 11 is not the town's: " + cache.describe(cache.lookup(world, scene.worldX(11), scene.worldZ(3)))
                            + "; warnings " + cache.warnings();
                }
                return cache.lookup(world, scene.worldX(12), scene.worldZ(3)).isWilderness() ? null : "x 12 is not wilderness";
            })
            .act("count territory changes", () -> scene.onEvent(PlayerTerritoryChangeEvent.class, event -> {
                if (event.getPlayer().getUniqueId().equals(walker.uuid())) {
                    changes.incrementAndGet();
                }
            }))
            .act("the player joins", walker::join)
            .await("they are online", 200, walker::online)
            .await("they have left the join's loading hold", 200, () -> walker.received("You're all set!"))
            .act("stand in the district", () -> walker.teleport(3.5, 2, 3.5))
            .waitTicks(2)
            .act("forget the messages so far", () -> {
                walker.clearMessages();
                changes.set(0);
            })
            .act("walk to x 11, still within range", () -> walker.teleport(11.5, 2, 3.5))
            .waitTicks(5)
            .check("nothing changed", () -> changes.get() == 0 ? null
                    : changes.get() + " change(s); got " + walker.recentMessages())
            .act("one block further, x 12", () -> walker.teleport(12.5, 2, 3.5))
            .await("the exit line is shown", 60, () -> walker.received("Leaving " + TOWN))
            .check("one change", () -> changes.get() == 1 ? null : changes.get() + " changes")
            .act("one block back, x 11", () -> walker.teleport(11.5, 2, 3.5))
            .await("the entry line is shown", 60, () -> walker.received("Entering the territory of " + TOWN))
            .check("two changes in all", () -> changes.get() == 2 ? null : changes.get() + " changes");
    }
}
