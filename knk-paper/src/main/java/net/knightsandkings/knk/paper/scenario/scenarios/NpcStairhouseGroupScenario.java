package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcMovementScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * Plan 4c.5 item 3 (link M1, PandiO/knk-workspace#621), in report mode: a group of 20 in {@code ranks} walks from the
 * street north of the stair house through the entrance (1413 49 -508), along the straight at 1414 49 -503 and up the
 * two steps into the hall, once (the developer's schematic). Reports the leader's result, the folds and narrowings with their reasons and places
 * (decision 2.8), the longest a follower stood in one block in the entrance, stuck spells, lag and the tick cost.
 * Later links check: folds only at 1-wide places with their reasons, no follower still in the entrance for more than
 * 10 s, no stuck spell left open (M3, M4). Needs Citizens.
 */
public final class NpcStairhouseGroupScenario implements Scenario<ScenarioScene> {

    static final int MAX_TICKS = 75 * 20;
    static final int MEMBERS = 20;

    private final boolean collisionOn;

    public NpcStairhouseGroupScenario(boolean collisionOn) {
        this.collisionOn = collisionOn;
    }

    @Override
    public String name() {
        return NpcMovementReport.name("npc-stairhouse-group", collisionOn);
    }

    @Override
    public String description() {
        return "Report: a group of 20 in ranks through the stair house's entrance and up the steps (collision "
            + (collisionOn ? "on" : "off") + ")";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcMovementScene house = NpcMovementScene.stairHouse(scene);
        NpcMovementReport report = new NpcMovementReport(scene, name());
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<NavGroup> group = new AtomicReference<>();
        AtomicReference<NavigationResult> result = new AtomicReference<>();
        AtomicReference<String> built = new AtomicReference<>();
        NpcMovementReport.StillInArea entrance = new NpcMovementReport.StillInArea();
        long[] started = {0};
        long[] took = {-1};
        plan.act("paste the stair house", () -> built.set(house.build()))
            .act("start NPC navigation on Citizens", () -> npcs.set(ScenarioNpcs.start(scene, house.houseLoop(),
                NpcMovementReport.settings(collisionOn))))
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .act("a group of 20 in ranks walks from the street through the entrance and up the steps", () -> {
                List<NpcMover> members = new ArrayList<>();
                for (int i = 0; i < MEMBERS; i++) {
                    // the leader in front, the followers in a bunch behind it on the street north of the entrance
                    members.add(npcs.get().spawn(house.houseGroupSpot(i)));
                }
                report.phaseStarts(npcs.get());
                started[0] = npcs.get().ticks();
                group.set(npcs.get().group(members, Formation.Type.RANKS));
                group.get().navigate(house.houseGoal(), r -> {
                    result.set(r);
                    took[0] = npcs.get().ticks() - started[0];
                });
                npcs.get().everyTick(() -> {
                    for (NpcMover mover : members) {
                        if (mover.id() == group.get().leader().id()) {
                            continue;
                        }
                        NavPoint at = mover.position().orElse(null);
                        entrance.look(mover, at, result.get() == null && at != null && house.inHouseEntrance(at), house);
                    }
                });
            })
            .await("the group's trip ends or the time is up", MAX_TICKS + 20,
                () -> result.get() != null || npcs.get().ticks() - started[0] >= MAX_TICKS)
            .check("report the numbers (report mode, M1)", () -> {
                report.add("scene", built.get())
                    .add("leader", result.get() == null ? "still walking after " + MAX_TICKS / 20 + " s"
                        : result.get().getClass().getSimpleName() + String.format(Locale.ROOT, " after %.1f s", took[0] / 20.0))
                    .add("width now", group.get().width().describe())
                    .widthChanges(npcs.get(), house)
                    .add("longest follower still in the entrance 1413 -508", entrance.describe())
                    .add("furthest behind its slot", String.format(Locale.ROOT, "%.1f blocks", group.get().maxLag()))
                    .add("lost", group.get().lost())
                    .add("rejoins", report.count(npcs.get(),
                        net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event.GROUP_REJOIN))
                    .common(npcs.get(), house)
                    .log("a group of 20 in ranks");
                return null;
            });
    }
}
