package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcMovementScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * Plan 4c.5 item 3 (link M1, PandiO/knk-workspace#621): a group of 20 in {@code ranks} walks from the street north of
 * the stair house through the entrance (1413 49 -508), along the straight at 1414 49 -503 and up the two steps into
 * the hall, once (the developer's schematic). Reports the leader's result, the folds, narrowings and merges with their
 * reasons and places (decision 2.8), the longest a follower stood in one block in the entrance, stuck spells, lag and
 * the tick cost. Link M3 (PandiO/knk-workspace#624) runs it with {@code group-formation-keeping: elastic} (and the
 * corridor and local-leg keys on) and checks plan 4c.4 item 3.3: the leader arrives, folds at most the number of
 * 1-wide places on the line, no stuck spell left open or not freed, nobody stuck near a wall, and (plan 4c.5 item 3)
 * no follower still in the entrance for more than 10 s. Link M4 (PandiO/knk-workspace#626) adds {@code crowd-awareness}
 * and checks plan 4c.4 item 4.3: 0 stuck spells (so none for a waiting follower), no queue held anyone longer than it
 * needed, and reports the crowd's waits and the cells NPCs shared (M3's note on lane merges with collision on). Needs
 * Citizens.
 */
public final class NpcStairhouseGroupScenario implements Scenario<ScenarioScene>, net.knightsandkings.knk.paper.scenario.SizedScene {

    /** The whole stair house (link B1, PandiO/knk-workspace#670). */
    @Override
    public ScenarioScene.Box box() {
        return NpcMovementScene.STAIRHOUSE_BOX;
    }

    static final int MAX_TICKS = 75 * 20;
    static final int MEMBERS = 20;
    /** Plan 4c.5 item 3: no follower still in the entrance for more than 10 s. */
    static final int MAX_ENTRANCE_TICKS = 10 * 20;

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
                NpcMovementReport.settings(collisionOn, true, true, true, false, true))))
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
            .check("folds only at 1-wide places, no stuck spell open, none near a wall, the entrance clear (M3)", () -> {
                int oneWide = NpcMovementReport.oneWidePlaces(
                    new net.knightsandkings.knk.paper.npc.nav.BukkitNavWorld(null), house.houseLine());
                String failed = report.groupFailures(npcs.get(), result.get(), oneWide);
                if (entrance.longestTicks() > MAX_ENTRANCE_TICKS) {
                    failed = (failed == null ? "" : failed + "; ") + "a follower still in the entrance for "
                        + entrance.describe();
                }
                String crowd = report.crowdFailures(npcs.get()); // link M4: 0 stuck spells, no queue held too long
                if (crowd != null) {
                    failed = (failed == null ? "" : failed + "; ") + crowd;
                }
                report.add("scene", built.get())
                    .add("formation keeping", "elastic")
                    .add("1-wide places", oneWide)
                    .add("leader", result.get() == null ? "still walking after " + MAX_TICKS / 20 + " s"
                        : result.get().getClass().getSimpleName() + String.format(Locale.ROOT, " after %.1f s", took[0] / 20.0))
                    .add("width now", group.get().width().describe())
                    .widthChanges(npcs.get(), house)
                    .add("longest follower still in the entrance 1413 -508", entrance.describe())
                    .add("furthest behind its slot", String.format(Locale.ROOT, "%.1f blocks", group.get().maxLag()))
                    .add("lost", group.get().lost())
                    .add("rejoins", report.count(npcs.get(),
                        net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event.GROUP_REJOIN))
                    .crowd(npcs.get())
                    .common(npcs.get(), house)
                    .corridor(npcs.get())
                    .log("a group of 20 in ranks");
                return failed;
            });
    }
}
