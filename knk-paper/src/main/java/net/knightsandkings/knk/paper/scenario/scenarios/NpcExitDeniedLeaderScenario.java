package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcCornerScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * PandiO/knk-workspace#701 (plan Phase 4 known limit 4, developer test step 15) on a real server: an NPC walks out of a
 * district on a route planned while leaving was allowed, and the district's exit is denied in the web app (realtime:
 * {@code EntityChanged}) mid-trip. The rules are read as on a server, from a region → domain map through
 * {@code DomainEntryRule}, and the fake road answers as the router does from inside a region the NPC may not leave
 * (BLOCKED). The NPC stops at the edge, inside, and waits; once leaving is allowed again ({@code /knk cache refresh}
 * this time) it walks on and arrives. Nobody trespasses. Needs Citizens and WorldGuard.
 */
public final class NpcExitDeniedLeaderScenario implements Scenario<ScenarioScene> {

    static final int TIMEOUT = 60 * 20;
    /** The district covers the north road west of x 24: the trip leaves it eastwards there. */
    static final int EDGE_X = 24;

    @Override
    public String name() {
        return "npc-exit-denied-leader";
    }

    @Override
    public String description() {
        return "Exit from a district is denied through realtime while an NPC walks out: it stops at the edge inside, "
            + "walks on once leaving is allowed again";
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
        AtomicReference<NpcMover> npc = new AtomicReference<>();
        List<NavigationResult> results = new CopyOnWriteArrayList<>();
        double edge = scene.worldX(EDGE_X);
        double[] furthestX = {Double.NEGATIVE_INFINITY};
        boolean[] denied = {false};
        NavPoint goal = corners.feet(18.5, NpcCornerScene.GROUND, 26.5);
        domains.onBeforeSwitch(() -> { // #724: the guards look before the re-warm's answers switch, as on a server
            if (npcs.get() != null) {
                npcs.get().lookBeforeRulesChange();
            }
        });
        domains.onEvent(() -> npcs.get().rulesChanged()); // road navigation's availability event, handed on

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
                    NpcMover m = npc.get();
                    if (m != null && denied[0]) {
                        m.position().ifPresent(p -> furthestX[0] = Math.max(furthestX[0], p.x()));
                    }
                });
            })
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .act("an NPC in the district walks east, out of it", () -> {
                npc.set(npcs.get().spawn(corners.feet(8.5, NpcCornerScene.GROUND, 4.5)));
                npcs.get().trace(npc.get());
                npcs.get().service().navigate(npc.get().id(), goal, results::add);
            })
            .await("it is half way to the edge", TIMEOUT, () -> npc.get().position()
                .map(p -> p.x() >= edge - 9).orElse(false))
            .act("leaving the district is denied in the web app (realtime: EntityChanged for the District)", () -> {
                domains.api(ScenarioDomainRules.domain(districtId, "Old Town", district, true, false, "District"));
                domains.realtime("District", districtId);
            })
            .await("the denial reached the NPC (coalescer, re-warm, availability event)", 5 * 20,
                () -> domains.events() == 1)
            .act("watch where it goes from here", () -> denied[0] = true)
            .check("it was still inside when the denial arrived", () -> npc.get().position()
                .map(p -> p.blockX() < (int) edge).orElse(false) ? null
                : "already out, at " + npc.get().position().orElse(null) + npcs.get().tail(20))
            .check("the district denies leaving in the map", () -> domains.mapped(district)
                .map(d -> Boolean.FALSE.equals(d.allowExit())).orElse(false) ? null
                : "the map has " + domains.mapped(district))
            .waitTicks(100)
            .check("it stopped at the edge, inside", () -> {
                NavPoint at = npc.get().position().orElseThrow();
                if (furthestX[0] >= edge || at.blockX() >= (int) edge) {
                    return "left the district, at " + at + " (furthest x " + furthestX[0] + ")" + npcs.get().tail(20);
                }
                return at.x() >= edge - 4 ? null : "stopped short of the edge, at " + at + npcs.get().tail(20);
            })
            .check("it reported the block", () -> results.stream().anyMatch(r -> r instanceof NavigationResult.Blocked)
                ? null : "results " + results + npcs.get().tail(20))
            .act("leaving is allowed again (/knk cache refresh: cleared, event, re-warm, event)", () -> {
                domains.api(ScenarioDomainRules.domain(districtId, "Old Town", district, true, true, "District"));
                domains.refresh();
            })
            .await("it walks on and arrives", TIMEOUT, () -> results.stream()
                .anyMatch(r -> r instanceof NavigationResult.Arrived))
            .check("nobody trespassed", () -> npcs.get().metrics().total(Event.TRESPASS) == 0 ? null
                : "trespasses " + npcs.get().metrics().total(Event.TRESPASS) + npcs.get().tail(20));
    }
}
