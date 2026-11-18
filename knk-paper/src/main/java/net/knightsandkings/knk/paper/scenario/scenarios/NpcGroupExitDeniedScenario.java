package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcCornerScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * PandiO/knk-workspace#701 (plan Phase 4 known limit 4, developer test step 15) on a real server: a group of 8 walks
 * out of a district (WorldGuard regions over the corner scene: a town over the scene, the district over the north road
 * west of x 24), and leaving the district is denied in the web app (realtime: {@code EntityChanged}) when the leader
 * and the first followers are out. The rules are read as on a server, from a region → domain map through
 * {@code DomainEntryRule}. The followers inside when the denial arrives stop at the edge, inside; the leader and the
 * followers already out walk on. Once leaving is allowed again (realtime) the rest follow. Nobody trespasses. Needs
 * Citizens and WorldGuard.
 */
public final class NpcGroupExitDeniedScenario implements Scenario<ScenarioScene> {

    static final int TIMEOUT = 60 * 20;
    /** The district covers the north road west of x 24: the group leaves it eastwards there. */
    static final int EDGE_X = 24;

    @Override
    public String name() {
        return "npc-group-exit-denied";
    }

    @Override
    public String description() {
        return "Exit from a district is denied through realtime with a group half inside: inside followers stop at the "
            + "edge, the rest walk on, all follow after";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcCornerScene corners = NpcCornerScene.in(scene);
        String town = scene.uniqueName("town");
        String district = scene.uniqueName("oldtown");
        int districtId = scene.fakeId(2);
        ScenarioDomainRules domains = new ScenarioDomainRules(scene, Set.of(town, district));
        domains.api(ScenarioDomainRules.domain(scene.fakeId(1), "Town", town, true, true, "Town"));
        domains.api(ScenarioDomainRules.domain(districtId, "Old Town", district, true, true, "District"));
        domains.warm();
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        List<NpcMover> members = new ArrayList<>();
        List<NpcMover> outside = new ArrayList<>();
        List<NpcMover> inside = new ArrayList<>();
        Map<Integer, Double> zAtEvent = new HashMap<>();
        Map<Integer, Double> furthestX = new HashMap<>();
        Map<Integer, Double> furthestZ = new HashMap<>();
        double edge = scene.worldX(EDGE_X);
        NavPoint goal = corners.feet(18.5, NpcCornerScene.GROUND, 26.5);
        domains.onEvent(() -> {
            if (domains.events() == 1) {
                // the first event: the denial reaches the NPCs now; who is inside and outside from here on
                for (NpcMover mover : members.subList(1, members.size())) {
                    NavPoint at = mover.position().orElseThrow();
                    zAtEvent.put(mover.id(), at.z());
                    (at.blockX() < (int) edge ? inside : outside).add(mover);
                }
                furthestX.clear();
            }
            npcs.get().rulesChanged(); // road navigation's availability event, as the rule listener hands it on
        });

        plan.act("build the corner scene, a town and a district over the north road west of x 24", () -> {
                corners.build();
                ScenarioRegions.cuboid(scene, town, 0, 0, 0, ScenarioScene.SIZE - 1, NpcCornerScene.HEIGHT,
                    ScenarioScene.SIZE - 1);
                ScenarioRegions.cuboid(scene, district, 0, 0, 0, EDGE_X - 1, NpcCornerScene.HEIGHT, 6);
            })
            .cleanup("remove the regions", () -> ScenarioRegions.removeAll(scene, town, district))
            .act("start NPC navigation on Citizens with the domain rules", () -> {
                ScenarioNpcs started = ScenarioNpcs.start(scene, corners.routeLine(), NavigationSettings.defaults(),
                    domains.rules(), List.of());
                npcs.set(started);
                started.everyTick(() -> {
                    for (NpcMover mover : members) {
                        mover.position().ifPresent(p -> {
                            furthestX.merge(mover.id(), p.x(), Math::max);
                            furthestZ.merge(mover.id(), p.z(), Math::max);
                        });
                    }
                });
            })
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .act("a group of 8 in a column walks east down the north road, out of the district", () -> {
                for (int i = 0; i < 8; i++) {
                    members.add(npcs.get().spawn(corners.feet(20.5 - 1.5 * i, NpcCornerScene.GROUND, 4.5)));
                }
                npcs.get().patrolGroup(members, Formation.Type.COLUMN, goal, corners.start());
            })
            .await("the leader is out", TIMEOUT, () -> members.get(0).position()
                .map(p -> p.x() >= edge + 2).orElse(false))
            .act("leaving the district is denied in the web app (realtime: EntityChanged for the District)", () -> {
                domains.api(ScenarioDomainRules.domain(districtId, "Old Town", district, true, false, "District"));
                domains.realtime("District", districtId);
            })
            .await("the denial reached the NPCs (coalescer, re-warm, availability event)", 5 * 20,
                () -> domains.events() == 1)
            .check("followers are inside and outside", () -> inside.size() >= 2 && outside.size() >= 1 ? null
                : "inside " + inside.size() + ", outside " + outside.size())
            .check("the district denies leaving in the map", () -> domains.mapped(district)
                .map(d -> Boolean.FALSE.equals(d.allowExit())).orElse(false) ? null
                : "the map has " + domains.mapped(district))
            .waitTicks(80)
            .check("the followers inside stopped at the edge, inside", () -> {
                for (NpcMover mover : inside) {
                    NavPoint at = mover.position().orElseThrow();
                    if (at.blockX() >= (int) edge || furthestX.get(mover.id()) >= edge) {
                        return mover.name() + " left the district, at " + at + npcs.get().tail(20);
                    }
                }
                return null;
            })
            .check("the ones outside walked on", () -> {
                for (NpcMover mover : outside) {
                    if (furthestZ.get(mover.id()) < zAtEvent.get(mover.id()) + 3) {
                        return mover.name() + " stopped outside" + npcs.get().tail(20);
                    }
                }
                return null;
            })
            .act("leaving is allowed again the same way", () -> {
                domains.api(ScenarioDomainRules.domain(districtId, "Old Town", district, true, true, "District"));
                domains.realtime("District", districtId);
            })
            .await("everybody follows out of the district", TIMEOUT, () -> {
                for (NpcMover mover : inside) {
                    if (furthestZ.get(mover.id()) < scene.worldZ(12)) {
                        return false;
                    }
                }
                return true;
            })
            .check("nobody trespassed", () -> npcs.get().metrics().total(Event.TRESPASS) == 0 ? null
                : "trespasses " + npcs.get().metrics().total(Event.TRESPASS) + npcs.get().tail(20));
    }
}
