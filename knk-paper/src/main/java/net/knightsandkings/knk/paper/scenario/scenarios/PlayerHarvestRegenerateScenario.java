package net.knightsandkings.knk.paper.scenario.scenarios;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * Harvest and regenerate (PandiO/knk-workspace#134, A16, smoke § 9.2) on a real server with scripted players. The scene is
 * a stone floor inside a WorldGuard region without members (as a production region denies {@code block-break}). A worker
 * holding the rule's node mines stone with a pickaxe: WorldGuard lets it through, no vanilla drop falls, the stone turns
 * to bedrock, the harvest is reported once and the worker gets the answer's 2 cobblestone; two seconds later the stone is
 * back. A visitor without the node is refused and nothing is reported. A block waiting to regenerate survives a restart of
 * the module and comes back after it.
 */
public final class PlayerHarvestRegenerateScenario implements Scenario<ScenarioScene> {

    private static final String REGION = "scn_harvest_pit";

    @Override
    public String name() {
        return "player-harvest-regenerate";
    }

    @Override
    public String description() {
        return "A qualified worker's stone break in a production area: bedrock placeholder, blueprint pay, stone back; a visitor is refused; a restart keeps the job";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        int workerId = scene.fakeId(3);
        int visitorId = scene.fakeId(4);
        ScenarioPlayer worker = scene.player("ScnHarvestWork", p -> p.account(workerId));
        ScenarioPlayer visitor = scene.player("ScnHarvestVisit", p -> p.account(visitorId));
        ScenarioHarvest harvest = new ScenarioHarvest();

        plan.act("build a stone floor inside a region without members", () -> {
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        scene.block(x, 0, z).setType(Material.STONE, false);
                    }
                }
                ScenarioRegions.cuboid(scene, REGION, 0, 0, 0, 31, 23, 31);
            })
            .cleanup("remove the region", () -> ScenarioRegions.removeAll(scene, REGION))
            .act("start player harvesting on a stand-in API", () -> harvest.start(scene, workerId))
            .cleanup("stop player harvesting", harvest::close)
            .act("the worker (with the node) and a visitor join", () -> {
                harvest.qualified.add(worker.uuid());
                worker.join();
                visitor.join();
            })
            .await("they are online", 200, () -> worker.online() && visitor.online())
            .await("they have left the join's loading hold", 200,
                () -> worker.received("You're all set!") && visitor.received("You're all set!"))
            .act("both stand on the floor holding an iron pickaxe", () -> {
                worker.teleport(8.5, 1, 8.5);
                visitor.teleport(12.5, 1, 12.5);
                worker.inventory().clear();
                worker.inventory().setItemInMainHand(new ItemStack(Material.IRON_PICKAXE));
                visitor.inventory().setItemInMainHand(new ItemStack(Material.IRON_PICKAXE));
            })
            .check("the worker mines the stone at (10, 0, 10)", () ->
                worker.player().breakBlock(scene.block(10, 0, 10)) ? null : "the break was refused")
            .await("the stone turned to bedrock", 20, () -> scene.block(10, 0, 10).getType() == Material.BEDROCK)
            .expectEquals("vanilla drops near the block", () -> ScenarioHarvest.dropsNear(scene, 10, 0, 10), 0L)
            .act("the waiting harvest is sent", () -> harvest.module.flush())
            .expectEquals("harvests reported", harvest.sent::size, 1)
            .expectEquals("breaks in the entry", () -> harvest.sent.get(0).breaks(), 1)
            .await("the worker got the answer's 2 cobblestone", 40, () -> ScenarioHarvest.cobbleIn(worker) == 2)
            .await("the stone is back", 100, () -> scene.block(10, 0, 10).getType() == Material.STONE)
            .check("the visitor's break is refused", () ->
                visitor.player().breakBlock(scene.block(14, 0, 14)) ? "the visitor broke the stone" : null)
            .expectEquals("the visitor's stone", () -> scene.block(14, 0, 14).getType(), Material.STONE)
            .await("the visitor is told why", 20, () -> visitor.received("you need the permission " + ScenarioHarvest.NODE))
            .act("the waiting harvests are sent", () -> harvest.module.flush())
            .expectEquals("harvests reported", harvest.sent::size, 1)
            .check("the worker mines (5, 0, 5)", () ->
                worker.player().breakBlock(scene.block(5, 0, 5)) ? null : "the break was refused")
            .await("it turned to bedrock", 20, () -> scene.block(5, 0, 5).getType() == Material.BEDROCK)
            .act("the module stops (a restart) before the answer and starts again", () -> {
                harvest.stop();
                harvest.start(scene, workerId);
            })
            .check("the job came back from disk", () -> harvest.module.queue().size() == 1
                && harvest.module.overlay().originalAt(scene.world().getName(), scene.worldX(5), scene.worldY(0),
                    scene.worldZ(5)) != null ? null : harvest.module.queue().size() + " jobs after the restart")
            .act("the saved harvest is sent", () -> harvest.module.flush())
            .expectEquals("harvests reported", harvest.sent::size, 2)
            .await("the stone at (5, 0, 5) is back", 100, () -> scene.block(5, 0, 5).getType() == Material.STONE)
            .expectEquals("cobblestone of the worker", () -> ScenarioHarvest.cobbleIn(worker), 4);
    }
}
