package net.knightsandkings.knk.paper.scenario.scenarios;

import com.sk89q.worldedit.math.BlockVector2;
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
 * A region drawn for a lootbox spawn area becomes {@code lootbox_area_<id>} with exactly the drawn outline and Y range
 * (lootbox area region smoke test § 1 step 4, PandiO/knk-workspace#34 and #487): an L-shaped
 * {@code tempregion_worldtask_<n>} five blocks high is renamed through the plugin's region callback server, the call
 * knk-web-api makes when the form is saved. Afterwards the temp region is gone and the area's region has the same
 * points and the same Y range (not the world's build height).
 */
public final class LootboxAreaRenameScenario implements Scenario<ScenarioScene> {

    private static final int MIN_Y = 3;
    private static final int MAX_Y = 7;
    private static final int[] OUTLINE = {4, 4, 20, 4, 20, 12, 12, 12, 12, 24, 4, 24};

    @Override
    public String name() {
        return "lootbox-area-rename-keeps-drawn-bounds";
    }

    @Override
    public String description() {
        return "A drawn region renamed to lootbox_area_<id> over the region callback keeps its outline and Y range";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        String temp = "tempregion_worldtask_" + scene.fakeId(10);
        String area = "lootbox_area_" + scene.fakeId(11);
        AtomicReference<List<BlockVector2>> drawn = new AtomicReference<>();
        AtomicReference<CompletableFuture<HttpResponse<String>>> call = new AtomicReference<>();
        plan.act("draw an L-shaped temp region from y 3 to 7", () ->
                drawn.set(ScenarioRegions.polygon(scene, temp, MIN_Y, MAX_Y, OUTLINE).getPoints()))
            .cleanup("remove the test regions", () -> ScenarioRegions.removeAll(scene, temp, area))
            .act("the API calls POST /Regions/rename for the saved area", () -> call.set(ScenarioRegions.callRename(scene,
                Map.of("oldRegionId", temp, "newRegionId", area, "world", scene.world().getName()))))
            .await("the region callback answered", 100, () -> call.get().isDone())
            .expectEquals("the callback's answer", () -> ScenarioRegions.answer(call.get()), "200 true")
            .expect("the temp region is gone", () -> !ScenarioRegions.manager(scene).hasRegion(temp))
            .check("the area's region has the drawn outline and Y range", () -> {
                ProtectedRegion renamed = ScenarioRegions.manager(scene).getRegion(area);
                return ScenarioRegions.shapeProblem(renamed, drawn.get(), scene.worldY(MIN_Y), scene.worldY(MAX_Y));
            });
    }
}
