package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcCornerScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * Real NPCs round the wall corners (implementation plan 4b.2 item 18.2, PandiO/knk-workspace#521, the acceptance of
 * #508): 6 single NPCs and a group of 6 in {@code ranks} walk the corner test scene for 90 s with the decided defaults.
 * No NPC stands in one block for more than 3 s, every stuck spell is freed within 2 recovery steps, nobody falls,
 * nobody trespasses, and the counters add up. Needs Citizens (CI's {@code paper-boot} server has a pinned build).
 */
public final class NpcCornersWalkScenario implements Scenario<ScenarioScene> {

    static final int WALK_TICKS = 90 * 20;

    @Override
    public String name() {
        return "npc-corners-walk";
    }

    @Override
    public String description() {
        return "6 NPCs and a group of 6 walk the corner scene for 90 s: never still over 3 s, freed in 2 steps, no falls";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcCornerScene corners = NpcCornerScene.in(scene);
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        plan.act("build the corner scene", corners::build)
            .act("start NPC navigation on Citizens", () -> npcs.set(ScenarioNpcs.start(scene, corners.routeLine(),
                NavigationSettings.defaults())))
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .act("6 NPCs walk the loop one by one", () -> {
                for (int i = 0; i < 6; i++) {
                    NpcMover mover = npcs.get().spawn(corners.feet(8.5 + 2 * i, NpcCornerScene.GROUND, 4.5));
                    npcs.get().patrol(mover, corners.stairCorner(), corners.start());
                }
            })
            .waitTicks(20)
            .act("a group of 6 in ranks walks it too", () -> {
                List<NpcMover> members = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    members.add(npcs.get().spawn(corners.feet(26.5 - (i % 2), NpcCornerScene.GROUND, 5.5 + i)));
                }
                npcs.get().patrolGroup(members, Formation.Type.RANKS, corners.stairCorner(), corners.start());
            })
            .waitTicks(WALK_TICKS)
            .check("they walked: trips were completed", () -> npcs.get().metrics().total(Event.ARRIVED) >= 6 ? null
                : "only " + npcs.get().metrics().total(Event.ARRIVED) + " arrivals: " + npcs.get().stats()
                    + npcs.get().tail(30))
            .check("no NPC stood in one block for more than 3 s", () -> npcs.get().longestStillTicks() <= 60 ? null
                : npcs.get().stats() + npcs.get().tail(30))
            .check("every stuck spell was freed within 2 recovery steps", () -> {
                var spells = npcs.get().metrics().spells();
                return spells.maxSteps() <= 2 && npcs.get().metrics().total(Event.NOT_FREED) == 0 ? null
                    : npcs.get().stats() + npcs.get().tail(30);
            })
            .check("nobody fell", () -> npcs.get().metrics().total(Event.FELL) == 0 ? null
                : npcs.get().stats() + npcs.get().tail(30))
            .check("nobody trespassed", () -> npcs.get().metrics().total(Event.TRESPASS) == 0 ? null
                : "trespasses " + npcs.get().metrics().total(Event.TRESPASS))
            .check("the counters add up", () -> npcs.get().countersAddUp());
    }
}
