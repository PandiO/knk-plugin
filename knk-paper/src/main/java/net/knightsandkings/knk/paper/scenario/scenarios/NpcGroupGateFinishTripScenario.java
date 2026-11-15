package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcCornerScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * Followers that finish their trip through a real gate (implementation plan 4b.3 item 14.2, PandiO/knk-workspace#507):
 * a portcullis across the corner scene's east road closes behind the leader of a group of 6 and opens after the
 * group turned back. With {@code group-followers-finish-trip: true} the held followers walk on to their slots at the
 * goal first (the member listener once each), then follow the group; with {@code false} they follow the leader at once
 * and none finishes a trip of its own. Nobody is moved by the door, nobody trespasses. Needs Citizens.
 */
public final class NpcGroupGateFinishTripScenario implements Scenario<ScenarioScene> {

    static final int TIMEOUT = 60 * 20;
    /** The door across the east road: x 25..27 at z 7, three high. */
    static final int DOOR_Z = 7;
    /** How long the door takes to close or open. */
    static final int DOOR_TICKS = 60;

    @Override
    public String name() {
        return "npc-group-gate-finish-trip";
    }

    @Override
    public String description() {
        return "A gate closes behind a group's leader: finish-trip on, held followers reach the goal first; off, they follow";
    }

    /** One run of the group with the toggle set one way. */
    private static final class Run {
        NavGroup group;
        final List<Integer> held = new ArrayList<>();
        final Map<Integer, Integer> arrivals = new HashMap<>();
        long finishedBefore;
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcCornerScene corners = NpcCornerScene.in(scene);
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<ScenarioGates.Portcullis> gate = new AtomicReference<>();
        List<String> pushed = new ArrayList<>();
        Map<Integer, NavPoint> last = new HashMap<>();
        // further than hop-blocks (16) from the start, so the leader's order is a road route with the gate crossing
        // on it, not a short trip of one local leg (which knows no gates)
        NavPoint goal = corners.feet(26.5, NpcCornerScene.GROUND, 24.5);
        plan.act("build the corner scene with an open portcullis across the east road", () -> {
                corners.build();
                // a 3-second close, as a town gate: a follower within the door's margin when the close starts walks
                // on through before it shuts (L2's rule, plan Phase 4 known limit 1); the others stop short
                gate.set(ScenarioGates.loadPortcullis(scene, 25, 2, DOOR_Z, 3, 3, Material.STONE_BRICKS, true,
                    DOOR_TICKS));
            })
            .cleanup("unload the portcullis", () -> {
                if (gate.get() != null) {
                    gate.get().unload();
                }
            })
            .act("start NPC navigation on Citizens with the gate's rules", () -> {
                ScenarioNpcs started = ScenarioNpcs.start(scene, corners.routeLine(), NavigationSettings.defaults(),
                    new ScenarioNpcRules(scene.services().gates()), List.of(new RoadPlanner.GateCrossing(
                        gate.get().doorId(), corners.floor(26.5, NpcCornerScene.GROUND, DOOR_Z + 0.5))));
                npcs.set(started);
                // nobody is moved by the door: no NPC jumps more than a block and a half in one tick
                started.everyTick(() -> {
                    for (NpcMover mover : started.movers()) {
                        NavPoint at = mover.position().orElse(null);
                        NavPoint before = at == null ? null : last.put(mover.id(), at);
                        if (before != null && before.horizontalDistance(at) > 1.5 && pushed.size() < 10) {
                            pushed.add(mover.name() + " from " + before + " to " + at);
                        }
                    }
                });
            })
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            });
        Run on = new Run();
        Run off = new Run();
        phase(plan, scene, corners, npcs, gate, goal, on, true);
        phase(plan, scene, corners, npcs, gate, goal, off, false);
        plan.check("nobody was moved by the door", () -> pushed.isEmpty() ? null : "moved: " + pushed)
            .check("nobody trespassed", () -> npcs.get().metrics().total(Event.TRESPASS) == 0 ? null
                : "trespasses " + npcs.get().metrics().total(Event.TRESPASS) + npcs.get().tail(20));
    }

    /** Waits for {@code done}; at the timeout the step fails with {@code why} (the run log) instead of a bare timeout. */
    static void awaitOrExplain(ScenarioPlan plan, String description, int timeoutTicks,
                               java.util.function.BooleanSupplier done, java.util.function.Supplier<String> why) {
        int[] ticks = {0};
        plan.await(description, timeoutTicks + 5, () -> done.getAsBoolean() || ++ticks[0] >= timeoutTicks)
            .check(description, () -> done.getAsBoolean() ? null : "not within " + timeoutTicks + " ticks: " + why.get());
    }

    private void phase(ScenarioPlan plan, ScenarioScene scene, NpcCornerScene corners, AtomicReference<ScenarioNpcs> npcs,
                       AtomicReference<ScenarioGates.Portcullis> gate, NavPoint goal, Run run, boolean finish) {
        String toggle = finish ? "finish-trip" : "follow-leader";
        double doorZ = scene.worldZ(DOOR_Z);
        plan.act(toggle + ": a group of 6 in a column walks from the north road through the gate", () -> {
                List<NpcMover> members = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    members.add(npcs.get().spawn(corners.feet(22.5 - 1.5 * i, NpcCornerScene.GROUND, 4.5)));
                }
                run.finishedBefore = npcs.get().metrics().total(Event.GROUP_FINISH_TRIP);
                run.group = npcs.get().patrolGroup(members, npcs.get().groupSettings(members.get(0), Formation.Type.COLUMN)
                    .withFollowersFinishTrip(finish), goal, corners.start());
                run.group.onMemberArrived((member, at) -> run.arrivals.merge(member.id(), 1, Integer::sum));
            })
            .await(toggle + ": the leader is past the gate", TIMEOUT, () -> run.group.leader().position()
                .map(p -> p.z() >= doorZ + 4.5).orElse(false)) // well past it: the leader's own route is not replanned
            .act(toggle + ": the gate closes behind the leader", () -> {
                gate.get().gates().closeGate(gate.get().doorId());
                npcs.get().gateChanged(gate.get().doorId());
            })
            .waitTicks(DOOR_TICKS + 20)
            .act(toggle + ": note the followers held before the door", () -> {
                for (NpcMover mover : npcs.get().movers()) {
                    if (run.group.followerState(mover.id()) != null
                        && mover.position().map(p -> p.z() < doorZ - 1).orElse(false)) {
                        run.held.add(mover.id());
                    }
                }
            })
            .check(toggle + ": followers are held before the closed door", () -> !run.held.isEmpty() ? null
                : "nobody held" + npcs.get().tail(20))
            .act(toggle + ": wait for the leader at the goal", () -> { });
        awaitOrExplain(plan, toggle + ": the leader arrives at the goal", TIMEOUT, () -> run.group.leader().position()
            .map(p -> p.horizontalDistance(goal) <= 2.5).orElse(false), () -> "leader at "
            + run.group.leader().position().orElse(null) + npcs.get().tail(30));
        plan.waitTicks(40)
            .act(toggle + ": the gate opens after the group turned back", () -> {
                gate.get().gates().openGate(gate.get().doorId());
                npcs.get().gateChanged(gate.get().doorId());
            });
        if (finish) {
            awaitOrExplain(plan, "finish-trip: every held follower reached its slot at the goal", TIMEOUT,
                    () -> run.arrivals.keySet().containsAll(run.held),
                    () -> "held " + run.held + ", arrived " + run.arrivals + ", finishing now " + run.group.finishing()
                        + npcs.get().tail(40));
            plan.check("finish-trip: each reported once", () -> {
                    for (int id : run.held) {
                        if (run.arrivals.getOrDefault(id, 0) != 1) {
                            return "arrivals " + run.arrivals + " for held " + run.held;
                        }
                    }
                    return npcs.get().metrics().total(Event.GROUP_FINISH_TRIP) - run.finishedBefore >= run.held.size()
                        ? null : "group_finish_trip " + npcs.get().metrics().total(Event.GROUP_FINISH_TRIP);
                })
                .await("finish-trip: then they follow the group", 200, () -> run.group.finishing() == 0);
        } else {
            int[] finishing = {0};
            int[] watched = {0};
            plan.await("follow-leader: 15 s after the gate opened", 305, () -> {
                    finishing[0] = Math.max(finishing[0], run.group.finishing());
                    return ++watched[0] >= 300;
                })
                .check("follow-leader: the held followers follow the leader, none finishes a trip", () ->
                    finishing[0] == 0 && run.arrivals.isEmpty()
                        && npcs.get().metrics().total(Event.GROUP_FINISH_TRIP) == run.finishedBefore ? null
                        : "finishing at most " + finishing[0] + ", member listener " + run.arrivals + npcs.get().tail(20));
        }
        plan.act(toggle + ": dismiss the group", () -> npcs.get().dismiss(run.group));
    }
}
