package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.Material;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Boxes in an area drawn below a roof stand on the floor, never on the roof (lootbox area region smoke test § 1
 * step 6, PandiO/knk-workspace#487): a hall with a stone floor at y 2 and a stone roof at y 9; the area's region
 * covers y 3 to 5 only. The column's surface is the roof, above the region, so the scheduler must take the highest
 * standable spot inside the region: every box is asked for at y 3.
 */
public final class LootboxSpawnUnderRoofScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "lootbox-spawns-under-roof";
    }

    @Override
    public String description() {
        return "Boxes in an area drawn under a roof stand on the floor inside the region, not on the roof";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        String region = scene.uniqueName("hall");
        AtomicReference<ScenarioLootboxes> lootboxes = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> refresh = new AtomicReference<>();
        plan.act("build a hall: floor at y 2, roof at y 9", () -> {
                ScenarioGates.floor(scene, 4, 4, 27, 27, 2, Material.STONE);
                ScenarioGates.floor(scene, 4, 4, 27, 27, 9, Material.STONE);
            })
            .act("draw the area's region inside the hall, y 3 to 5", () ->
                ScenarioRegions.cuboid(scene, region, 4, 3, 4, 27, 5, 27))
            .cleanup("remove the region", () -> ScenarioRegions.removeAll(scene, region))
            .act("start the plugin's spawn scheduler with the area in the runtime config", () -> {
                lootboxes.set(new ScenarioLootboxes(scene));
                KnkLootboxRuntimeConfig config = ScenarioLootboxes.runtimeConfig(true, ScenarioLootboxes.area(scene, 50, region));
                refresh.set(lootboxes.get().serve(config));
                lootboxes.get().start();
            })
            .cleanup("stop the spawn scheduler", () -> {
                if (lootboxes.get() != null) lootboxes.get().stop();
            })
            .await("the runtime config was read", 40, () -> refresh.get().isDone())
            .await("4 spawn requests", 20 * 10, () -> lootboxes.get().requests().size() >= 4)
            .check("every box stands on the floor, under the roof", () -> {
                for (ScenarioLootboxes.SpawnRequest request : lootboxes.get().requests()) {
                    if (request.y() != scene.worldY(3)) {
                        return "a box was asked for at y " + request.y() + " (floor " + scene.worldY(3) + ", roof top "
                            + scene.worldY(10) + ")";
                    }
                }
                return null;
            });
    }
}
