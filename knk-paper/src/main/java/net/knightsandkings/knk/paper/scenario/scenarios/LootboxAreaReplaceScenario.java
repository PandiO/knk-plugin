package net.knightsandkings.knk.paper.scenario.scenarios;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Running an area's region step again redefines {@code lootbox_area_<id>} in place (lootbox area region smoke test § 2
 * step 2, KNG-103 and PandiO/knk-workspace#487): the area's region exists with a greeting flag and priority 5; a new,
 * differently shaped temp region is renamed onto it with {@code replaceRegionId} set to the area's region, as the API
 * does. Afterwards there is one region with the area's name, the new outline and Y range, the old flag and priority,
 * and no temp region left behind.
 */
public final class LootboxAreaReplaceScenario implements Scenario<ScenarioScene> {

    private static final String GREETING = "scenario greeting";

    @Override
    public String name() {
        return "lootbox-area-rerun-replaces-in-place";
    }

    @Override
    public String description() {
        return "A re-drawn area region replaces lootbox_area_<id> in place, with the new shape and the old flags";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        String area = "lootbox_area_" + scene.fakeId(20);
        String temp = "tempregion_worldtask_" + scene.fakeId(21);
        AtomicReference<List<BlockVector2>> redrawn = new AtomicReference<>();
        AtomicReference<CompletableFuture<HttpResponse<String>>> call = new AtomicReference<>();
        plan.act("the area's region exists with a greeting flag and priority 5", () -> {
                ProtectedRegion existing = ScenarioRegions.polygon(scene, area, 2, 10, 2, 2, 12, 2, 12, 12, 2, 12);
                existing.setFlag(Flags.GREET_MESSAGE, GREETING);
                existing.setPriority(5);
            })
            .cleanup("remove the test regions", () -> ScenarioRegions.removeAll(scene, area, temp))
            .act("draw a different shape from y 4 to 6 as a temp region", () ->
                redrawn.set(ScenarioRegions.polygon(scene, temp, 4, 6, 14, 14, 28, 14, 21, 28).getPoints()))
            .act("the API renames it, replacing the area's region", () -> call.set(ScenarioRegions.callRename(scene,
                Map.of("oldRegionId", temp, "newRegionId", area, "world", scene.world().getName(),
                    "replaceRegionId", area))))
            .await("the region callback answered", 100, () -> call.get().isDone())
            .expectEquals("the callback's answer", () -> ScenarioRegions.answer(call.get()), "200 true")
            .expect("no temp region is left behind", () -> !ScenarioRegions.manager(scene).hasRegion(temp))
            .check("the area's region has the new outline and Y range", () -> ScenarioRegions.shapeProblem(
                ScenarioRegions.manager(scene).getRegion(area), redrawn.get(), scene.worldY(4), scene.worldY(6)))
            .check("the greeting flag and priority are kept", () -> {
                RegionManager manager = ScenarioRegions.manager(scene);
                ProtectedRegion replaced = manager.getRegion(area);
                String greeting = replaced.getFlag(Flags.GREET_MESSAGE);
                if (!GREETING.equals(greeting)) {
                    return "greeting is " + greeting;
                }
                return replaced.getPriority() == 5 ? null : "priority is " + replaced.getPriority();
            });
    }
}
