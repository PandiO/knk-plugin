package net.knightsandkings.knk.paper.scenario.scenarios;

import com.sk89q.worldedit.math.BlockVector2;
import net.knightsandkings.knk.core.production.survey.AreaShape;
import net.knightsandkings.knk.core.production.survey.AreaSpec;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.production.SurveyRegionShapes;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A region drawn for a production area becomes {@code production_area_<id>} with exactly the drawn outline and Y range,
 * and the survey finds it under that name (production area region, PandiO/knk-workspace#946): an L-shaped
 * {@code tempregion_worldtask_<n>} is renamed through the plugin's region callback server, the call knk-web-api makes
 * when the ProductionArea form is saved. Afterwards the temp region is gone, the area's region has the drawn points and
 * Y range, an area naming the temp region would no longer resolve ("WorldGuard region … not found", what the 14-day
 * cleanup used to cause), and the area as the API now sends it resolves through {@link SurveyRegionShapes} to the L.
 */
public final class ProductionAreaRegionRenameScenario implements Scenario<ScenarioScene> {

    private static final int MIN_Y = 3;
    private static final int MAX_Y = 7;
    private static final int[] OUTLINE = {4, 4, 20, 4, 20, 12, 12, 12, 12, 24, 4, 24};

    @Override
    public String name() {
        return "production-area-region-rename-keeps-drawn-bounds";
    }

    @Override
    public String description() {
        return "A drawn region renamed to production_area_<id> keeps its outline and Y range, and the survey finds it";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        int areaId = scene.fakeId(50);
        String temp = "tempregion_worldtask_" + scene.fakeId(51);
        String finalName = "production_area_" + areaId;
        AtomicReference<List<BlockVector2>> drawn = new AtomicReference<>();
        AtomicReference<CompletableFuture<HttpResponse<String>>> call = new AtomicReference<>();
        plan.act("draw an L-shaped temp region from y 3 to 7", () ->
                drawn.set(ScenarioRegions.polygon(scene, temp, MIN_Y, MAX_Y, OUTLINE).getPoints()))
            .cleanup("remove the test regions", () -> ScenarioRegions.removeAll(scene, temp, finalName))
            .act("the API calls POST /Regions/rename for the saved area", () -> call.set(ScenarioRegions.callRename(scene,
                Map.of("oldRegionId", temp, "newRegionId", finalName, "world", scene.world().getName()))))
            .await("the region callback answered", 100, () -> call.get().isDone())
            .expectEquals("the callback's answer", () -> ScenarioRegions.answer(call.get()), "200 true")
            .expect("the temp region is gone", () -> !ScenarioRegions.manager(scene).hasRegion(temp))
            .check("the area's region has the drawn outline and Y range", () -> ScenarioRegions.shapeProblem(
                ScenarioRegions.manager(scene).getRegion(finalName), drawn.get(), scene.worldY(MIN_Y), scene.worldY(MAX_Y)))
            .check("the survey resolves the area under its final name, not under the temp name", () -> {
                String world = scene.world().getName();
                SurveyRegionShapes shapes = new SurveyRegionShapes();
                int bottom = scene.world().getMinHeight();
                int top = scene.world().getMaxHeight();
                try {
                    area(areaId, world, temp).resolve(shapes, bottom, top);
                    return "the temp name still resolves";
                } catch (IllegalArgumentException expected) {
                    // the temp region is gone, as after the old 14-day cleanup
                }
                AreaShape shape = area(areaId, world, finalName).resolve(shapes, bottom, top);
                if (!shape.contains(scene.worldX(8), scene.worldY(MIN_Y + 1), scene.worldZ(20))) {
                    return "a block inside the L is not in the area";
                }
                return shape.contains(scene.worldX(16), scene.worldY(MIN_Y + 1), scene.worldZ(20))
                    ? "a block in the L's notch is in the area" : null;
            });
    }

    private static AreaSpec area(int areaId, String world, String regionId) {
        return new AreaSpec(areaId, areaId, "Scenario farm", "Scenario field", "Region", true, true, world, regionId,
            List.of(), null, null, null, null, null, null, null, null, null, null);
    }
}
