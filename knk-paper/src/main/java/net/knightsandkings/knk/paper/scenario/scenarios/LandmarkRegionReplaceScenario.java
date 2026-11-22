package net.knightsandkings.knk.paper.scenario.scenarios;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldguard.protection.managers.RegionManager;
import net.knightsandkings.knk.core.roads.route.RegionShape;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.navigation.WorldGuardRegionShapes;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Running a landmark's region step again redefines {@code landmark_<id>} in place, and a temp region left from an
 * earlier failed rename goes (landmark smoke test, PandiO/knk-workspace#945, as KNG-103 does for domains): the
 * landmark's region exists; a new, differently shaped temp region is renamed onto it with {@code replaceRegionId} set to
 * the landmark's region, as the API does. Afterwards there is one region with the landmark's name and the new outline
 * and Y range, and the navigation's region shapes read the new shape at once (they are not cached). Then a landmark
 * still on a temp name (its rename failed) is re-run: the new temp region takes the final name and the old temp region
 * is removed.
 */
public final class LandmarkRegionReplaceScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "landmark-region-rerun-replaces-in-place";
    }

    @Override
    public String description() {
        return "A re-drawn landmark region replaces landmark_<id> in place; a previous temp region is removed";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        String landmark = "landmark_" + scene.fakeId(40);
        String temp = "tempregion_worldtask_" + scene.fakeId(41);
        String other = "landmark_" + scene.fakeId(42);
        String oldTemp = "tempregion_worldtask_" + scene.fakeId(43);
        String newTemp = "tempregion_worldtask_" + scene.fakeId(44);
        AtomicReference<List<BlockVector2>> redrawn = new AtomicReference<>();
        AtomicReference<CompletableFuture<HttpResponse<String>>> call = new AtomicReference<>();
        AtomicReference<CompletableFuture<HttpResponse<String>>> secondCall = new AtomicReference<>();
        plan.act("the landmark's region exists", () -> ScenarioRegions.polygon(scene, landmark, 2, 10, 2, 2, 12, 2, 12, 12, 2, 12))
            .cleanup("remove the test regions", () -> ScenarioRegions.removeAll(scene, landmark, temp, other, oldTemp, newTemp))
            .act("draw a different shape from y 4 to 6 as a temp region", () ->
                redrawn.set(ScenarioRegions.polygon(scene, temp, 4, 6, 14, 14, 28, 14, 21, 28).getPoints()))
            .act("the API renames it, replacing the landmark's region", () -> call.set(ScenarioRegions.callRename(scene,
                Map.of("oldRegionId", temp, "newRegionId", landmark, "world", scene.world().getName(),
                    "replaceRegionId", landmark))))
            .await("the region callback answered", 100, () -> call.get().isDone())
            .expectEquals("the callback's answer", () -> ScenarioRegions.answer(call.get()), "200 true")
            .expect("no temp region is left behind", () -> !ScenarioRegions.manager(scene).hasRegion(temp))
            .check("the landmark's region has the new outline and Y range", () -> ScenarioRegions.shapeProblem(
                ScenarioRegions.manager(scene).getRegion(landmark), redrawn.get(), scene.worldY(4), scene.worldY(6)))
            .check("the navigation reads the new shape", () -> {
                Optional<RegionShape> shape = new WorldGuardRegionShapes().shape(scene.world().getName(), landmark);
                if (shape.isEmpty()) {
                    return "no shape for " + landmark;
                }
                if (shape.get().contains(scene.worldX(5), scene.worldY(5), scene.worldZ(5))) {
                    return "the old shape is still read";
                }
                return shape.get().contains(scene.worldX(21), scene.worldY(5), scene.worldZ(18)) ? null
                    : "a cell inside the new shape is not in it";
            })
            .act("a landmark still on a temp name is re-drawn", () -> {
                ScenarioRegions.polygon(scene, oldTemp, 2, 6, 40, 40, 48, 40, 48, 48, 40, 48);
                ScenarioRegions.polygon(scene, newTemp, 2, 6, 50, 50, 58, 50, 58, 58, 50, 58);
            })
            .act("the API renames the new one, replacing the old temp region", () -> secondCall.set(ScenarioRegions.callRename(scene,
                Map.of("oldRegionId", newTemp, "newRegionId", other, "world", scene.world().getName(),
                    "replaceRegionId", oldTemp))))
            .await("the second callback answered", 100, () -> secondCall.get().isDone())
            .expectEquals("the second callback's answer", () -> ScenarioRegions.answer(secondCall.get()), "200 true")
            .check("the final name exists and both temp regions are gone", () -> {
                RegionManager manager = ScenarioRegions.manager(scene);
                if (!manager.hasRegion(other)) {
                    return other + " is missing";
                }
                if (manager.hasRegion(newTemp)) {
                    return newTemp + " was not renamed";
                }
                return manager.hasRegion(oldTemp) ? oldTemp + " was left behind" : null;
            });
    }
}
