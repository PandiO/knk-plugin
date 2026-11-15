package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.lootbox.KnkLootboxArea;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.Material;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Switching lootboxes off in the web app stops spawning after the next runtime refresh (settings pages smoke test
 * § 1 step 6, the game half): the plugin's spawn scheduler asks for boxes in an area while the API's runtime config
 * says lootboxes are on (the control); after a refresh that reads them switched off, no further box is asked for in
 * three seconds, three of the area's one-second intervals.
 */
public final class LootboxSwitchedOffScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "lootbox-switched-off-spawns-nothing";
    }

    @Override
    public String description() {
        return "After the runtime refresh reads lootboxes switched off, the scheduler asks for no more boxes";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        String region = scene.uniqueName("area");
        AtomicReference<ScenarioLootboxes> lootboxes = new AtomicReference<>();
        AtomicReference<KnkLootboxArea> area = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> refresh = new AtomicReference<>();
        AtomicInteger beforeOff = new AtomicInteger();
        plan.act("a stone field with the area's region over it", () -> {
                ScenarioGates.floor(scene, 4, 4, 27, 27, 2, Material.STONE);
                ScenarioRegions.cuboid(scene, region, 4, 3, 4, 27, 7, 27);
            })
            .cleanup("remove the region", () -> ScenarioRegions.removeAll(scene, region))
            .act("start the spawn scheduler with lootboxes on", () -> {
                lootboxes.set(new ScenarioLootboxes(scene));
                area.set(ScenarioLootboxes.area(scene, 60, region));
                refresh.set(lootboxes.get().serve(ScenarioLootboxes.runtimeConfig(true, area.get())));
                lootboxes.get().start();
            })
            .cleanup("stop the spawn scheduler", () -> {
                if (lootboxes.get() != null) lootboxes.get().stop();
            })
            .await("boxes are asked for while lootboxes are on (control)", 20 * 8,
                () -> refresh.get().isDone() && lootboxes.get().requests().size() >= 2)
            .act("the web app switches lootboxes off; the runtime refresh reads it", () ->
                refresh.set(lootboxes.get().serve(ScenarioLootboxes.runtimeConfig(false, area.get()))))
            .await("the refresh was applied", 40, () -> refresh.get().isDone())
            .waitTicks(10)
            .act("count the requests made so far", () -> beforeOff.set(lootboxes.get().requests().size()))
            .waitTicks(60)
            .expectEquals("boxes asked for in the 3 s after the switch", () -> lootboxes.get().requests().size()
                - beforeOff.get(), 0);
    }
}
