package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.navigation.shape.NavGeometry;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.scenario.SizedScene;
import net.knightsandkings.knk.paper.scenario.SnapshotReplay;

/**
 * PandiO/knk-workspace#953 on the real stair: the developer's snapshot of {@code hatch-tower} (dev server, 2026-11-21
 * 14:56, plugin {@code 0.1.0+6f6c915f}) replayed. In the smoke run of 2026-11-21 (15:51 to 16:03, collision on) single
 * NPCs walking down the tower's spiral stair (x 1403 to 1404, z -539 to -536, feet 94 down to 86) logged "pushed 0.4
 * blocks … by something unknown" at almost every turn with nobody touching them, then walked back up a step and down
 * again: 19 pushes and 1757 walks back in three trips, two teleports home.
 * <p>
 * {@value #SNAPSHOT} is that zip trimmed to its own box ({@code tools/scenario-scenes/trim_snapshot.py}: x 1392..1429,
 * y 75..119, z -551..-508). One NPC with the stored settings (collision on, players never push, the movement keys on)
 * walks from the foot of the tower's ladder (1400 99 -540, the room under the roof hatch; the ladder itself is
 * PandiO/knk-workspace#956) down the spiral stair and out to the route's start on the keep's road. It must arrive, with
 * no push and no walk back.
 */
public final class NpcHatchTowerStairReplayScenario implements Scenario<ScenarioScene>, SizedScene {

    /** The committed, trimmed snapshot, in the jar's {@value SnapshotReplay#RESOURCE_FOLDER}. */
    static final String SNAPSHOT = "hatch-tower.zip";
    /** The developer's trips down took 63 s with the walk-back loop; a clean one takes about half. */
    static final int TRIP_TICKS = 120 * 20;
    /** The foot of the ladder, in the room under the roof hatch, and the route's start on the keep's road (feet). */
    static final double[] START = {1400.5, 99, -539.5};
    static final double[] GOAL = {1419.5, 82, -542.5};

    private SnapshotReplay replay;

    private SnapshotReplay replay() {
        if (replay == null) {
            replay = SnapshotReplay.fromResource(SNAPSHOT);
        }
        return replay;
    }

    @Override
    public String name() {
        return "npc-replay-hatch-tower-stair";
    }

    @Override
    public String description() {
        return "The developer's snapshot of hatch-tower replayed with collision on: one NPC down the tower's spiral "
            + "stair to the keep's road, with no push and no walk back";
    }

    @Override
    public ScenarioScene.Box box() {
        return NpcSnapshotReplayScenario.boxOf(replay().snapshot());
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        SnapshotReplay replay = replay();
        AtomicReference<SnapshotReplay.Placed> placed = new AtomicReference<>();
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<NavigationResult> result = new AtomicReference<>();
        List<String> rejected = new ArrayList<>();
        long[] started = {0, -1};
        NpcMover[] npc = new NpcMover[1];
        plan.act("place the snapshot's blocks", () -> placed.set(replay.placeBlocks(scene)))
            .check("the blocks are placed, the start's floor and the goal's are there", () -> {
                SnapshotReplay.Placed p = placed.get();
                if (p.placed() == 0 || p.skippedChunks() != 0 || !p.problems().isEmpty()) {
                    return "placed " + p.describe() + " " + p.problems();
                }
                for (double[] real : List.of(START, GOAL)) {
                    NavPoint under = replay.inScene(scene, new double[] {real[0], real[1] - 1, real[2]});
                    if (scene.world().getBlockAt(under.blockX(), under.blockY(), under.blockZ()).getType().isAir()) {
                        return "no floor under " + real[0] + " " + real[1] + " " + real[2];
                    }
                }
                return null;
            })
            .act("build the snapshot's regions", () -> replay.buildRegions(scene))
            .cleanup("remove the snapshot's regions", () -> replay.removeRegions(scene))
            .act("start NPC navigation with the snapshot's settings (collision on)", () -> {
                NavigationSettings settings = replay.settings(rejected,
                    stored -> stored.with(NavigationSettings.COLLIDABLE, true)
                        .with(NavigationSettings.PLAYERS_PUSH_NPCS, false)
                        .with(NavigationSettings.PUSH_RETURN, true));
                npcs.set(ScenarioNpcs.start(scene, replay.loop(scene), settings, replay.accessRules(scene),
                    replay.gates(scene)));
                npcs.get().plazas(replay.plazas(scene));
            })
            .cleanup("remove the NPC", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .check("every stored setting is known", () -> rejected.isEmpty() ? null : "rejected " + rejected)
            .act("one NPC walks from the foot of the ladder down the spiral stair to the keep's road", () -> {
                npc[0] = npcs.get().spawn(replay.inScene(scene, START));
                started[0] = npcs.get().ticks();
                if (!npcs.get().service().navigate(npc[0].id(), replay.inScene(scene, GOAL), r -> {
                    result.set(r);
                    started[1] = npcs.get().ticks() - started[0];
                })) {
                    throw new ScenarioAssertionError("the navigation service refused the order");
                }
            })
            .await("the trip ends or the time is up", TRIP_TICKS + 20,
                () -> result.get() != null || npcs.get().ticks() - started[0] >= TRIP_TICKS)
            .check("it arrives with no push and no walk back", () -> {
                ScenarioNpcs n = npcs.get();
                long[] p = n.service().pushCounts();
                long returns = n.log().stream().filter(l -> l.text().contains(" returns ")).count();
                String line = String.format(Locale.ROOT, "down the spiral stair: %s in %s, pushes %d (players %d, "
                        + "attacks %d), walks back %d, returned %d, teleported %d, falls %d",
                    result.get() == null ? "no result" : result.get().getClass().getSimpleName(),
                    started[1] < 0 ? "-" : String.format(Locale.ROOT, "%.1f s", started[1] / 20.0), p[0], p[1], p[2],
                    returns, p[3], p[6], n.metrics().total(Event.FELL));
                scene.services().plugin().getLogger().info(NpcMovementReport.TAG + " " + name() + " | " + line);
                List<String> failed = new ArrayList<>();
                if (!(result.get() instanceof NavigationResult.Arrived)) {
                    failed.add("the trip did not end at the goal: " + (result.get() == null ? "still walking after "
                        + TRIP_TICKS / 20 + " s at " + npc[0].position().map(at -> NavGeometry.block(
                            new NavPoint(at.world(), replay.real(scene, at)[0], replay.real(scene, at)[1],
                                replay.real(scene, at)[2]))).orElse("-") : result.get()));
                }
                if (p[0] != 0) {
                    failed.add(p[0] + " pushes with nothing touching it");
                }
                if (returns != 0) {
                    failed.add(returns + " walks back");
                }
                return failed.isEmpty() ? null : String.join("; ", failed) + n.tail(25);
            })
            .act("remove the NPC", () -> npcs.get().stop());
    }
}
