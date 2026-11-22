package net.knightsandkings.knk.paper.scenario.scenarios;

import com.sk89q.worldedit.math.BlockVector2;
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
 * A region drawn for a landmark becomes {@code domain_<id>} (a landmark is a domain since PandiO/knk-workspace#985, LD14; #945 named it {@code domain_<id>}) with exactly the drawn outline and Y range, and the
 * navigation reads it under that name (landmark smoke test, PandiO/knk-workspace#945): an L-shaped
 * {@code tempregion_worldtask_<n>} five blocks high is renamed through the plugin's region callback server, the call
 * knk-web-api makes when the Landmark form is saved. Afterwards the temp region is gone, the landmark's region has the
 * same points and Y range, and the navigation's region shapes ({@link WorldGuardRegionShapes}, which
 * {@code ModelLandmarks} and the region destination read for a landmark whose extent is {@code domain_<id>}) resolve
 * that name to the drawn L: a cell inside it is in, one in its notch is not.
 */
public final class LandmarkRegionRenameScenario implements Scenario<ScenarioScene> {

    private static final int MIN_Y = 3;
    private static final int MAX_Y = 7;
    private static final int[] OUTLINE = {4, 4, 20, 4, 20, 12, 12, 12, 12, 24, 4, 24};

    @Override
    public String name() {
        return "landmark-region-rename-keeps-drawn-bounds";
    }

    @Override
    public String description() {
        return "A drawn landmark region renamed to domain_<id> keeps its outline and Y range, and the navigation finds it";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        String temp = "tempregion_worldtask_" + scene.fakeId(31);
        String finalName = "domain_" + scene.fakeId(30);
        AtomicReference<List<BlockVector2>> drawn = new AtomicReference<>();
        AtomicReference<CompletableFuture<HttpResponse<String>>> call = new AtomicReference<>();
        plan.act("draw an L-shaped temp region from y 3 to 7", () ->
                drawn.set(ScenarioRegions.polygon(scene, temp, MIN_Y, MAX_Y, OUTLINE).getPoints()))
            .cleanup("remove the test regions", () -> ScenarioRegions.removeAll(scene, temp, finalName))
            .act("the API calls POST /Regions/rename for the saved landmark", () -> call.set(ScenarioRegions.callRename(scene,
                Map.of("oldRegionId", temp, "newRegionId", finalName, "world", scene.world().getName()))))
            .await("the region callback answered", 100, () -> call.get().isDone())
            .expectEquals("the callback's answer", () -> ScenarioRegions.answer(call.get()), "200 true")
            .expect("the temp region is gone", () -> !ScenarioRegions.manager(scene).hasRegion(temp))
            .check("the landmark's region has the drawn outline and Y range", () -> ScenarioRegions.shapeProblem(
                ScenarioRegions.manager(scene).getRegion(finalName), drawn.get(), scene.worldY(MIN_Y), scene.worldY(MAX_Y)))
            .check("the navigation resolves the landmark's renamed region", () -> {
                String world = scene.world().getName();
                WorldGuardRegionShapes shapes = new WorldGuardRegionShapes();
                if (shapes.shape(world, temp).isPresent()) {
                    return "the temp name still resolves";
                }
                Optional<RegionShape> shape = shapes.shape(world, finalName);
                if (shape.isEmpty()) {
                    return "no shape for " + finalName;
                }
                if (!shape.get().containsFeet(scene.worldX(8) + 0.5, scene.worldY(MIN_Y + 1), scene.worldZ(20) + 0.5)) {
                    return "a cell inside the L is not in the landmark's region";
                }
                return shape.get().containsFeet(scene.worldX(16) + 0.5, scene.worldY(MIN_Y + 1), scene.worldZ(20) + 0.5)
                    ? "a cell in the L's notch is in the landmark's region" : null;
            });
    }
}
