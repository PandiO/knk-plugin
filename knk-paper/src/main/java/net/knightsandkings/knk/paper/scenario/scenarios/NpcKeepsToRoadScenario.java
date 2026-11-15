package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.Material;

import java.util.concurrent.atomic.AtomicReference;

/**
 * An NPC keeps to the road around an obstacle (NPC navigation Phase 2 developer test step 4, "distance from the road
 * line", PandiO/knk-workspace#543): a stone block stands on the straight line between start and goal, and the road
 * makes a wide U around it (north 11 blocks, along, back south). The open grass between the block and the road is a
 * shorter way any path search would take; the NPC must follow the road, never more than {@link #MAX_OFF_ROAD} blocks
 * from its centre line, and arrive. NPCs never cross terrain in a straight line or cut across to save distance.
 */
public final class NpcKeepsToRoadScenario implements Scenario<ScenarioScene> {

    /** The road is 3 blocks wide (1.5 either side of its centre line); half a block more for a corner. */
    static final double MAX_OFF_ROAD = 2.0;
    static final int TIMEOUT_TICKS = 20 * 45;

    @Override
    public String name() {
        return "npc-keeps-to-road-around-obstacle";
    }

    @Override
    public String description() {
        return "An NPC follows the road around an obstacle instead of cutting across the open ground beside it";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<ScenarioRoadNpcs> npcs = new AtomicReference<>();
        AtomicReference<NpcMover> npc = new AtomicReference<>();
        AtomicReference<AtomicReference<NavigationResult>> result = new AtomicReference<>();
        double[] worst = {0};
        AtomicReference<NavPoint> worstAt = new AtomicReference<>();
        plan.act("lay a road that bends around the obstacle's place and start NPC navigation on it", () ->
                npcs.set(new ScenarioRoadNpcs(scene, new int[] {3, 16}, new int[] {6, 16}, new int[] {6, 27},
                    new int[] {25, 27}, new int[] {25, 16}, new int[] {28, 16}).start()))
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) npcs.get().close();
            })
            .act("put a stone block 3 high on the straight line between the road's ends", () -> {
                for (int y = ScenarioRoadNpcs.FEET_Y; y < ScenarioRoadNpcs.FEET_Y + 3; y++) {
                    ScenarioGates.floor(scene, 11, 12, 20, 20, y, Material.STONE);
                }
            })
            .act("spawn an NPC at the west end and send it to the east end", () -> {
                npc.set(npcs.get().spawn(3.5, 16.5));
                result.set(npcs.get().navigate(npc.get(), 28.5, 16.5));
            })
            .await("the NPC's order ended (its distance from the road checked every tick)", TIMEOUT_TICKS, () -> {
                npc.get().position().ifPresent(at -> {
                    double off = npcs.get().distanceFromRoad(at);
                    if (off > worst[0]) {
                        worst[0] = off;
                        worstAt.set(at);
                    }
                });
                return result.get().get() != null;
            })
            .check("it never left the road", () -> {
                scene.services().plugin().getLogger().info("[Scenario] " + name() + ": furthest from the road's centre line "
                    + Math.round(worst[0] * 100) / 100.0 + " blocks");
                return worst[0] <= MAX_OFF_ROAD ? null : "it was " + Math.round(worst[0] * 10) / 10.0
                    + " blocks from the road's centre line at " + npcs.get().describe(worstAt.get()) + " (at most "
                    + MAX_OFF_ROAD + ")";
            })
            .check("the NPC arrived", () -> result.get().get() instanceof NavigationResult.Arrived ? null
                : "the order ended as " + result.get().get() + "; log " + npcs.get().log());
    }
}
