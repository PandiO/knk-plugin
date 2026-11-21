package net.knightsandkings.knk.paper.scenario.scenarios;

import org.bukkit.Material;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.inventory.ItemStack;

import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * A felled tree comes back by snapshot (PandiO/knk-workspace#134, P-D8, smoke § 9.2) on a real server: a worker breaks
 * the bottom log of a small oak (four logs, a leaf crown) in a logger's area. The whole tree goes at once (no log or
 * leaves left floating, no vanilla drops), a sapling stands at its base and does not grow, the four logs are reported as
 * four breaks, and the exact tree is back after the regeneration time.
 */
public final class PlayerHarvestTreeScenario implements Scenario<ScenarioScene> {

    @Override
    public String name() {
        return "player-harvest-tree";
    }

    @Override
    public String description() {
        return "Breaking a log in a logger's area fells the whole tree, leaves a sapling, reports every log and restores the tree";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        int workerId = scene.fakeId(3);
        ScenarioPlayer worker = scene.player("ScnHarvestTree", p -> p.account(workerId));
        ScenarioHarvest harvest = new ScenarioHarvest();

        plan.act("build a grass floor and a small oak at (16, 1, 16)", () -> {
                for (int x = 0; x < 32; x++) {
                    for (int z = 0; z < 32; z++) {
                        scene.block(x, 0, z).setType(Material.GRASS_BLOCK, false);
                    }
                }
                for (int y = 1; y <= 4; y++) {
                    scene.block(16, y, 16).setType(Material.OAK_LOG, false);
                }
                for (int x = 15; x <= 17; x++) {
                    for (int z = 15; z <= 17; z++) {
                        for (int y = 4; y <= 5; y++) {
                            if (scene.block(x, y, z).getType() == Material.AIR) {
                                scene.block(x, y, z).setType(Material.OAK_LEAVES, false);
                                Leaves leaves = (Leaves) scene.block(x, y, z).getBlockData();
                                leaves.setPersistent(false);
                                leaves.setDistance(Math.abs(x - 16) + Math.abs(z - 16) + (y == 5 ? 1 : 0)); // as the game counts it
                                scene.block(x, y, z).setBlockData(leaves, false);
                            }
                        }
                    }
                }
            })
            .act("start player harvesting on a stand-in API", () -> harvest.start(scene, workerId))
            .cleanup("stop player harvesting", harvest::close)
            .act("the worker (with the node) joins", () -> {
                harvest.qualified.add(worker.uuid());
                worker.join();
            })
            .await("they are online", 200, worker::online)
            .await("they have left the join's loading hold", 200, () -> worker.received("You're all set!"))
            .act("they stand next to the tree holding an axe", () -> {
                worker.teleport(13.5, 1, 16.5);
                worker.inventory().clear();
                worker.inventory().setItemInMainHand(new ItemStack(Material.IRON_AXE));
            })
            .check("the worker breaks the bottom log", () ->
                worker.player().breakBlock(scene.block(16, 1, 16)) ? null : "the break was refused")
            .await("the whole tree is gone and a sapling stands at its base", 20, () ->
                scene.block(16, 1, 16).getType() == Material.OAK_SAPLING
                    && scene.block(16, 3, 16).getType() == Material.AIR
                    && scene.block(16, 5, 16).getType() == Material.AIR
                    && scene.block(15, 4, 15).getType() == Material.AIR)
            .expectEquals("vanilla drops near the tree", () -> ScenarioHarvest.dropsNear(scene, 16, 2, 16), 0L)
            .check("bone meal does not grow the sapling while the tree waits", () -> {
                for (int i = 0; i < 6; i++) {
                    scene.block(16, 1, 16).applyBoneMeal(org.bukkit.block.BlockFace.UP);
                }
                return scene.block(16, 1, 16).getType() == Material.OAK_SAPLING
                    && scene.block(16, 2, 16).getType() == Material.AIR ? null : "the sapling grew";
            })
            .check("surveys count the tree whole meanwhile", () -> {
                var overlay = harvest.module.overlay();
                String world = scene.world().getName();
                return "OAK_LOG".equals(overlay.originalAt(world, scene.worldX(16), scene.worldY(3), scene.worldZ(16)))
                    && "OAK_LEAVES".equals(overlay.originalAt(world, scene.worldX(15), scene.worldY(4), scene.worldZ(15)))
                    ? null : "the overlay does not hold the tree";
            })
            .act("the waiting harvest is sent", () -> harvest.module.flush())
            .expectEquals("breaks reported (one per log)", () -> harvest.sent.get(0).breaks(), 4)
            .await("the worker got the answer's 8 cobblestone", 40, () -> ScenarioHarvest.cobbleIn(worker) == 8)
            .await("the tree is back: four logs and its crown", 100, () ->
                scene.block(16, 1, 16).getType() == Material.OAK_LOG
                    && scene.block(16, 4, 16).getType() == Material.OAK_LOG
                    && scene.block(15, 4, 15).getType() == Material.OAK_LEAVES
                    && scene.block(16, 5, 16).getType() == Material.OAK_LEAVES);
    }
}
