package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

import java.util.concurrent.atomic.AtomicReference;

/**
 * An NPC walks a short road route and arrives (NPC navigation Phase 2 developer test step 3, PandiO/knk-workspace#543):
 * a straight road of 25 blocks on the fake road network, one NPC at its west end sent to its east end. The road
 * route is planned on the road graph and handed to the Citizens NPC in stretches; the NPC arrives at the goal, and
 * on the open road no stuck event fires.
 */
public final class NpcWalksRoadRouteScenario implements Scenario<ScenarioScene> {

    static final int TIMEOUT_TICKS = 20 * 30;
    /** Within this many blocks (horizontally) of its goal an NPC counts as there, as in the navigation service. */
    static final double ARRIVAL_DISTANCE = 1.5;

    @Override
    public String name() {
        return "npc-walks-road-route";
    }

    @Override
    public String description() {
        return "An NPC follows a planned road route on the fake road network to its end and arrives";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<NpcMover> npc = new AtomicReference<>();
        AtomicReference<AtomicReference<NavigationResult>> result = new AtomicReference<>();
        plan.act("lay a straight road from x 3 to x 28 and start NPC navigation on it", () ->
                npcs.set(new ScenarioNpcs(scene, new int[] {3, 16}, new int[] {28, 16}).start()))
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) npcs.get().close();
            })
            .act("spawn an NPC at the west end and send it to the east end", () -> {
                npc.set(npcs.get().spawn(3.5, 16.5));
                result.set(npcs.get().navigate(npc.get(), 28.5, 16.5));
            })
            .await("the NPC's order ended", TIMEOUT_TICKS, () -> result.get().get() != null)
            .check("the NPC arrived", () -> result.get().get() instanceof NavigationResult.Arrived ? null
                : "the order ended as " + result.get().get() + "; log " + npcs.get().log())
            .check("it walked the road route (stretches handed over), not off-road hops", () ->
                npcs.get().count(Event.STRETCH) > 0 ? null : "no road stretch was handed to the NPC")
            .check("no stuck event on the open road", () -> {
                long stuck = npcs.get().count(Event.STUCK_ENGINE) + npcs.get().count(Event.STUCK_NO_PROGRESS);
                return stuck == 0 ? null : stuck + " stuck event(s): " + npcs.get().log();
            })
            .check("it stands at the goal", () -> {
                NavPoint at = npc.get().position().orElse(null);
                if (at == null) return "the NPC is not spawned";
                NavPoint goal = npcs.get().feet(28.5, 16.5);
                double off = Math.hypot(at.x() - goal.x(), at.z() - goal.z());
                scene.services().plugin().getLogger().info("[Scenario] " + name() + ": arrived "
                    + Math.round(off * 100) / 100.0 + " blocks from the goal");
                return off <= ARRIVAL_DISTANCE ? null : "it stands at " + npcs.get().describe(at) + ", " + Math.round(off * 10) / 10.0
                    + " blocks from the goal";
            });
    }
}
