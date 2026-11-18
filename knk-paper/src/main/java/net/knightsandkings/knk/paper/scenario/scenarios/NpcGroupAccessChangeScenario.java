package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcCornerScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * An access change in the middle of a group on a real server (implementation plan 4b.3 item 14.3; L2's behaviour of
 * PandiO/knk-workspace#506, now in CI): a group of 6 walks into a scenario WorldGuard region over the corner scene's
 * east road; entry is removed (fake access data) when half the column is inside. The followers outside stop at the
 * edge, the ones inside walk on, all follow when entry returns; nobody trespasses. Needs Citizens and WorldGuard.
 */
public final class NpcGroupAccessChangeScenario implements Scenario<ScenarioScene> {

    static final int TIMEOUT = 60 * 20;
    /** The region's edge: the east road from z 10 on. */
    static final int EDGE_Z = 10;

    @Override
    public String name() {
        return "npc-group-access-change";
    }

    @Override
    public String description() {
        return "Entry to a region is removed mid-group: followers outside stop at the edge, inside walk on, all follow after";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcCornerScene corners = NpcCornerScene.in(scene);
        String region = scene.uniqueName("keep");
        ScenarioNpcRules rules = new ScenarioNpcRules(null);
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<NavGroup> group = new AtomicReference<>();
        List<NpcMover> members = new ArrayList<>();
        List<NpcMover> outside = new ArrayList<>();
        List<NpcMover> inside = new ArrayList<>();
        Map<Integer, Double> zAtDeny = new HashMap<>();
        Map<Integer, Double> furthestZ = new HashMap<>();
        double edge = scene.worldZ(EDGE_Z);
        NavPoint goal = corners.feet(18.5, NpcCornerScene.GROUND, 26.5);
        plan.act("build the corner scene and a region over the east road from z 10", () -> {
                corners.build();
                ScenarioRegions.cuboid(scene, region, 24, 0, EDGE_Z, 28, NpcCornerScene.HEIGHT, 27);
            })
            .cleanup("remove the region", () -> ScenarioRegions.removeAll(scene, region))
            .act("start NPC navigation on Citizens with the scenario's access rules", () -> {
                ScenarioNpcs started = ScenarioNpcs.start(scene, corners.routeLine(), NavigationSettings.defaults(),
                    rules, List.of());
                npcs.set(started);
                started.everyTick(() -> {
                    for (NpcMover mover : members) {
                        mover.position().ifPresent(p -> furthestZ.merge(mover.id(), p.z(), Math::max));
                    }
                });
            })
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .act("a group of 6 in a column walks down the east road into the region", () -> {
                for (int i = 0; i < 6; i++) {
                    members.add(npcs.get().spawn(corners.feet(22.5 - 1.5 * i, NpcCornerScene.GROUND, 4.5)));
                }
                group.set(npcs.get().patrolGroup(members, Formation.Type.COLUMN, goal, corners.start()));
            })
            .await("half the column is inside", TIMEOUT, () -> members.get(0).position()
                .map(p -> p.z() >= edge + 3).orElse(false))
            .act("entry to the region is removed", () -> {
                for (NpcMover mover : members.subList(1, members.size())) {
                    NavPoint at = mover.position().orElseThrow();
                    zAtDeny.put(mover.id(), at.z());
                    (at.blockZ() >= (int) edge ? inside : outside).add(mover);
                }
                rules.deny(region);
                npcs.get().rulesChanged();
            })
            .check("followers are outside and inside", () -> outside.size() >= 2 && !inside.isEmpty() ? null
                : "outside " + outside.size() + ", inside " + inside.size())
            .waitTicks(80)
            .check("the followers outside stopped at the edge", () -> {
                for (NpcMover mover : outside) {
                    NavPoint at = mover.position().orElseThrow();
                    if (at.blockZ() >= (int) edge || furthestZ.get(mover.id()) >= edge) {
                        return mover.name() + " entered the region, at " + at + npcs.get().tail(20);
                    }
                }
                return null;
            })
            .check("the ones inside walked on", () -> {
                for (NpcMover mover : inside) {
                    if (furthestZ.get(mover.id()) < zAtDeny.get(mover.id()) + 3) {
                        return mover.name() + " stopped inside" + npcs.get().tail(20);
                    }
                }
                return null;
            })
            .act("entry returns", () -> {
                rules.allow(region);
                npcs.get().rulesChanged();
            })
            .await("everybody follows into the region", TIMEOUT, () -> {
                for (NpcMover mover : outside) {
                    if (furthestZ.get(mover.id()) < edge + 8) {
                        return false;
                    }
                }
                return true;
            })
            .check("nobody trespassed", () -> npcs.get().metrics().total(Event.TRESPASS) == 0 ? null
                : "trespasses " + npcs.get().metrics().total(Event.TRESPASS) + npcs.get().tail(20));
    }
}
