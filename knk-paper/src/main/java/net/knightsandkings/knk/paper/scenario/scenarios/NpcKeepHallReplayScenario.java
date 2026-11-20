package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.scenario.SizedScene;
import net.knightsandkings.knk.paper.scenario.SnapshotReplay;

/**
 * Link G1 (PandiO/knk-workspace#814): an NPC's trip to the keep storage hall (1417 59 -489), the developer's resource
 * transport destination, on the developer's snapshot of 2026-11-19 ({@code northern-wearway-upper-keep-tower}, dev
 * server 16:56, plugin {@code 0.1.0+6311de11}; its box 1271 36 -633 to 1429 125 -481 covers the hall). The trip is
 * routed on the snapshot's own road graph with the plugin's router ({@link ScenarioNpcs#startOnRoads}), with the
 * snapshot's settings and {@code road-goal-reachable} on. The road point nearest the hall is on the stair house's road
 * (F2's live case for players: a long walk from there).
 * <p>
 * {@value #SNAPSHOT} is that zip trimmed to x 1380..1429, y 36..100, z -560..-481 ({@code tools/scenario-scenes/
 * trim_snapshot.py}); the whole 17.6 MB zip is on knk-plugin {@code evidence/smoke-2026-11-16}, folder
 * {@code knk-paper/src/test/resources/scenario/evidence/2026-11-19/}.
 * <p>
 * A leader alone, then a group of 5 in a column, walk from the keep's road (1420 82 -531) to the hall. Each fails
 * when the leader does not arrive in time; the group also when a follower is lost or not in the hall. With
 * {@code -Dknk.scenario.roadGoalOff=true} the key is off (the before run).
 */
public final class NpcKeepHallReplayScenario implements Scenario<ScenarioScene>, SizedScene {

    static final String SNAPSHOT = "keep-hall.zip";
    static final int LEADER_TICKS = 150 * 20;
    static final int GROUP_TICKS = 180 * 20;
    static final int SETTLE_TICKS = 20 * 20;
    static final int FOLLOWERS = 4;
    /** The start on the keep's road and the storage hall (real feet). */
    static final double[] START = {1420.5, 82, -530.5};
    static final double[] HALL = {1417.5, 59, -488.5};
    /** An NPC within this many blocks of the hall's goal (and on its floor) is in it (a column ends 7.5 back). */
    static final double NEAR = 10;

    private SnapshotReplay replay;

    private SnapshotReplay replay() {
        if (replay == null) {
            replay = SnapshotReplay.fromResource(SNAPSHOT);
        }
        return replay;
    }

    @Override
    public String name() {
        return "npc-replay-keep-hall";
    }

    @Override
    public String description() {
        return "The developer's snapshot of 2026-11-19 replayed on its own road graph: a leader alone and a group of 5 "
            + "from the keep's road to the keep storage hall (road-goal-reachable)";
    }

    @Override
    public ScenarioScene.Box box() {
        return NpcSnapshotReplayScenario.boxOf(replay().snapshot());
    }

    private static NavPoint feet(ScenarioScene scene, SnapshotReplay replay, double[] real) {
        return replay.inScene(scene, real);
    }

    private static String real(ScenarioScene scene, SnapshotReplay replay, NpcMover m) {
        return m.position().map(p -> {
            double[] r = replay.real(scene, p);
            return String.format(Locale.ROOT, "%d %d %d", (int) Math.floor(r[0]), (int) Math.floor(r[1]),
                (int) Math.floor(r[2]));
        }).orElse("-");
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        SnapshotReplay replay = replay();
        AtomicReference<SnapshotReplay.Placed> placed = new AtomicReference<>();
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        List<String> rejected = new ArrayList<>();
        boolean on = !Boolean.getBoolean(NpcRoadGoalDeadEndScenario.ROAD_GOAL_OFF);
        plan.act("place the snapshot's blocks", () -> placed.set(replay.placeBlocks(scene)))
            .check("the blocks are placed, the hall's floor and the start's are there", () -> {
                SnapshotReplay.Placed p = placed.get();
                if (p.placed() == 0 || p.skippedChunks() != 0 || !p.problems().isEmpty()) {
                    return "placed " + p.describe() + " " + p.problems();
                }
                for (double[] r : List.of(START, HALL)) {
                    NavPoint under = feet(scene, replay, new double[] {r[0], r[1] - 1, r[2]});
                    if (scene.world().getBlockAt(under.blockX(), under.blockY(), under.blockZ()).getType().isAir()) {
                        return "no floor under " + r[0] + " " + r[1] + " " + r[2];
                    }
                }
                return null;
            })
            .act("build the snapshot's regions", () -> replay.buildRegions(scene))
            .cleanup("remove the snapshot's regions", () -> replay.removeRegions(scene))
            .act("start NPC navigation on the snapshot's road graph", () -> npcs.set(ScenarioNpcs.startOnRoads(scene,
                replay.roads(scene), replay.settings(rejected,
                    o -> o.with(NavigationSettings.ROAD_GOAL_REACHABLE, on)), replay.accessRules(scene))))
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .check("every stored setting is known", () -> rejected.isEmpty() ? null : "rejected " + rejected);

        AtomicReference<NavigationResult> leaderResult = new AtomicReference<>();
        List<NpcMover> alone = new ArrayList<>();
        long[] t = {0, -1};
        plan.act("a leader alone walks from the keep's road to the storage hall", () -> {
                NpcMover leader = npcs.get().spawn(feet(scene, replay, START));
                alone.add(leader);
                t[0] = npcs.get().ticks();
                if (!npcs.get().service().navigate(leader.id(), feet(scene, replay, HALL), r -> {
                    leaderResult.set(r);
                    t[1] = npcs.get().ticks() - t[0];
                })) {
                    throw new ScenarioAssertionError("the navigation service refused the leader's order");
                }
            })
            .await("the leader's trip ends or the time is up", LEADER_TICKS + 20,
                () -> leaderResult.get() != null || npcs.get().ticks() - t[0] >= LEADER_TICKS)
            .check("the leader alone arrives in the hall", () -> {
                report(npcs.get(), "a leader alone", leaderResult.get(), t[1]);
                String failure = leaderResult.get() instanceof NavigationResult.Arrived ? null
                    : "the leader did not arrive: " + (leaderResult.get() == null ? "still walking after "
                        + LEADER_TICKS / 20 + " s at " + real(scene, replay, alone.get(0)) : leaderResult.get());
                if (failure == null && !inTheHall(scene, replay, alone.get(0))) {
                    failure = "the leader ended outside the hall, at " + real(scene, replay, alone.get(0));
                }
                npcs.get().remove(new ArrayList<>(alone));
                return failure == null ? null : failure + npcs.get().tail(24);
            });

        AtomicReference<NavigationResult> groupResult = new AtomicReference<>();
        AtomicReference<NavGroup> group = new AtomicReference<>();
        List<NpcMover> members = new ArrayList<>();
        long[] g = {0, -1};
        plan.act("a group of 5 walks the same way", () -> {
                members.add(npcs.get().spawn(feet(scene, replay, START)));
                for (int i = 0; i < FOLLOWERS; i++) {
                    members.add(npcs.get().spawn(feet(scene, replay, new double[] {START[0], START[1],
                        START[2] - 2 - 1.5 * i})));
                }
                g[0] = npcs.get().ticks();
                group.set(npcs.get().group(members, Formation.Type.COLUMN));
                group.get().navigate(feet(scene, replay, HALL), r -> {
                    groupResult.set(r);
                    g[1] = npcs.get().ticks() - g[0];
                });
            })
            .await("the leader arrived " + SETTLE_TICKS / 20 + " s ago, or the time is up", GROUP_TICKS + 20, () -> {
                long now = npcs.get().ticks();
                return (groupResult.get() != null && now - g[0] - g[1] >= SETTLE_TICKS) || now - g[0] >= GROUP_TICKS;
            })
            .check("the group follows its leader into the hall", () -> {
                report(npcs.get(), "a group of 5", groupResult.get(), g[1]);
                List<String> failures = new ArrayList<>();
                if (!(groupResult.get() instanceof NavigationResult.Arrived)) {
                    failures.add("the leader did not arrive: " + groupResult.get());
                }
                for (NpcMover m : members) {
                    if (!inTheHall(scene, replay, m)) {
                        failures.add(m.name() + " is not in the hall: " + real(scene, replay, m));
                    }
                }
                if (group.get().lost() > 0) {
                    failures.add(group.get().lost() + " followers lost");
                }
                return failures.isEmpty() ? null : String.join("; ", failures) + npcs.get().tail(24);
            });
    }

    private static boolean inTheHall(ScenarioScene scene, SnapshotReplay replay, NpcMover m) {
        NavPoint hall = feet(scene, replay, HALL);
        NavPoint at = m.position().orElse(null);
        return at != null && at.horizontalDistance(hall) <= NEAR && Math.abs(at.y() - hall.y()) <= 2;
    }

    private void report(ScenarioNpcs npcs, String phase, NavigationResult result, long took) {
        org.bukkit.Bukkit.getLogger().info(String.format(Locale.ROOT, "KNK-NPC-REPORT %s %s: %s; road goal choices %d, "
                + "moved %d; off-road walks %d, in hops %d; stuck spells %d; falls %d", name(), phase,
            result == null ? "still walking" : result.getClass().getSimpleName() + String.format(Locale.ROOT,
                " after %.1f s", took / 20.0), npcs.service().roadGoalChoices(), npcs.service().roadGoalsMoved(),
            npcs.service().offRoadWalks(), npcs.service().offRoadHops(),
            npcs.metrics().total(Event.STUCK_ENGINE) + npcs.metrics().total(Event.STUCK_NO_PROGRESS),
            npcs.metrics().total(Event.FELL)) + "; " + lastChoice(npcs));
    }

    /** The last road goal choice in the run log (the candidates, their road and walk lengths and the chosen one). */
    static String lastChoice(ScenarioNpcs npcs) {
        String last = "no road goal choice";
        for (ScenarioNpcs.LogLine line : npcs.log()) {
            if (line.text().contains(" road goal for ")) {
                last = line.text();
            }
        }
        return last;
    }
}
