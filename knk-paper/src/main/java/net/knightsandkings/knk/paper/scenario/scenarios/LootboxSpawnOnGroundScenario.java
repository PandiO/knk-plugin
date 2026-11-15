package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.Material;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Boxes appear on the ground inside an area's drawn region (lootbox area region smoke test § 1 step 5,
 * PandiO/knk-workspace#487): a region drawn from y 3 to 7 over a field whose west half is a stone floor at y 2 and
 * whose east half has no ground in that range. The plugin's spawn scheduler, fed the area as the API's runtime
 * config, asks for every box on the floor (y 3) inside the region, never in the east half.
 */
public final class LootboxSpawnOnGroundScenario implements Scenario<ScenarioScene> {

    static final int REQUESTS = 6;

    @Override
    public String name() {
        return "lootbox-spawns-on-ground-in-range";
    }

    @Override
    public String description() {
        return "The spawn scheduler puts boxes on the ground inside an area's drawn Y range, never over a hole";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        String region = scene.uniqueName("area");
        AtomicReference<ScenarioLootboxes> lootboxes = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> refresh = new AtomicReference<>();
        plan.act("lay a stone floor at y 2 under the west half of the field", () ->
                ScenarioGates.floor(scene, 4, 4, 13, 27, 2, Material.STONE))
            .act("draw the area's region over the whole field, y 3 to 7", () ->
                ScenarioRegions.cuboid(scene, region, 4, 3, 4, 27, 7, 27))
            .cleanup("remove the region", () -> ScenarioRegions.removeAll(scene, region))
            .act("start the plugin's spawn scheduler with the area in the runtime config", () -> {
                lootboxes.set(new ScenarioLootboxes(scene));
                KnkLootboxRuntimeConfig config = ScenarioLootboxes.runtimeConfig(true, ScenarioLootboxes.area(scene, 40, region));
                refresh.set(lootboxes.get().serve(config));
                lootboxes.get().start();
            })
            .cleanup("stop the spawn scheduler", () -> {
                if (lootboxes.get() != null) lootboxes.get().stop();
            })
            .await("the runtime config was read", 40, () -> refresh.get().isDone())
            .await(REQUESTS + " spawn requests", 20 * (REQUESTS + 6), () -> lootboxes.get().requests().size() >= REQUESTS)
            .check("every box stands on the floor inside the region", () -> {
                for (ScenarioLootboxes.SpawnRequest request : lootboxes.get().requests()) {
                    boolean onFloor = request.x() >= scene.worldX(4) && request.x() <= scene.worldX(13)
                        && request.z() >= scene.worldZ(4) && request.z() <= scene.worldZ(27)
                        && request.y() == scene.worldY(3);
                    if (!onFloor || !request.world().equals(scene.world().getName())) {
                        return "a box was asked for at " + request.x() + "," + request.y() + "," + request.z()
                            + " (the floor is x " + scene.worldX(4) + "-" + scene.worldX(13) + ", y "
                            + scene.worldY(3) + ")";
                    }
                }
                return null;
            });
    }
}
