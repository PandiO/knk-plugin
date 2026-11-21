package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.npc.citizens.CitizensBootstrap;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * PandiO/knk-workspace#953: a walking NPC with collision on, on a road whose line doubles back at a junction, as
 * {@code northern-wearway-upper-keep-tower}'s does at 1338 44 -623 (the developer's snapshot of 2026-11-17: 1321 -624,
 * 1338 -623, 1337 -625, 1340 -622, 1346 -616). In the developer's smoke run of 2026-11-21 one NPC logged "pushed 0.4
 * blocks ... by something unknown" there with nothing touching it, then walked back to that spot 390 times and never
 * went on. Three trips on this line, the same shape moved to the scene:
 * <ol>
 *     <li>with the code defaults and collision on, nothing touches the NPC: no push, no walk back, it arrives;</li>
 *     <li>the same with the developer's movement keys (corridor steering, local-legs clearance, crowd awareness);</li>
 *     <li>with those keys, a knock of unknown kind (a velocity, as water, a piston or another plugin gives; no contact
 *     is reported) as it comes up to the hairpin: one push, one walk back, then on, and it arrives.</li>
 * </ol>
 * Each trip logs one {@value NpcMovementReport#TAG} line. Needs Citizens.
 */
public final class NpcPushReturnHairpinScenario implements Scenario<ScenarioScene> {

    static final int FLOOR = 0;
    static final int FEET = 1;
    /** Time for a trip of about 40 blocks (about 10 s at Citizens' walk); a walk back loop runs out of it. */
    static final int TRIP_TICKS = 30 * 20;
    /** The road line's floor points (scene x, z), once round the loop: east, back down the hairpin, on north-east. */
    static final double[][] LINE = {{1, 8}, {18, 9}, {17, 7}, {20, 10}, {26, 16}, {28, 22}, {28, 28}, {1, 28}, {1, 8}};
    static final double[] START = {2.5, 8.5};
    static final double[] GOAL = {28.5, 22.5};
    /** The knock: when the NPC's feet pass this scene x on the way to the hairpin, back west and a little up. */
    static final double KNOCK_AT_X = 14.0;
    static final Vector KNOCK = new Vector(-0.8, 0.2, 0);

    @Override
    public String name() {
        return "npc-push-return-hairpin";
    }

    @Override
    public String description() {
        return "A walking NPC with collision on, on a line that doubles back at a junction: no push from its own walk; "
            + "after a knock of unknown kind it walks back once and goes on with its trip";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        List<String> report = new ArrayList<>();
        plan.act("lay a stone floor", () -> {
            for (int x = 0; x < ScenarioScene.SIZE; x++) {
                for (int z = 0; z < ScenarioScene.SIZE; z++) {
                    scene.block(x, FLOOR, z).setType(Material.STONE);
                }
            }
        });
        trip(plan, scene, "defaults, nothing touches it", NavigationSettings.builder()
            .global(NavigationSettings.Overrides.of(NavigationSettings.COLLIDABLE, true)).build(), false, report);
        trip(plan, scene, "movement keys, nothing touches it", movementKeys(), false, report);
        trip(plan, scene, "movement keys, knocked before the hairpin", movementKeys(), true, report);
    }

    /** The developer's dev config of 2026-11-21: the main-run movement keys on, collision on, players never push. */
    static NavigationSettings movementKeys() {
        return NpcMovementReport.settings(true, true, true, true, false, true);
    }

    static List<NavPoint> line(ScenarioScene scene) {
        List<NavPoint> line = new ArrayList<>();
        for (double[] p : LINE) {
            line.add(ScenarioPushes.feet(scene, p[0], FLOOR, p[1]));
        }
        return line;
    }

    private static void trip(ScenarioPlan plan, ScenarioScene scene, String label, NavigationSettings settings,
                             boolean knock, List<String> report) {
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<NavigationResult> result = new AtomicReference<>();
        long[] started = {0, -1};
        boolean[] knocked = {false};
        NavPoint goal = ScenarioPushes.feet(scene, GOAL[0], FEET, GOAL[1]);
        double knockX = ScenarioPushes.feet(scene, KNOCK_AT_X, FEET, 0).x();
        plan.act("start an NPC at the road's west end (" + label + ")", () -> {
                npcs.set(ScenarioNpcs.start(scene, line(scene), settings));
                NpcMover npc = npcs.get().spawn(ScenarioPushes.feet(scene, START[0], FEET, START[1]));
                started[0] = npcs.get().ticks();
                if (!npcs.get().service().navigate(npc.id(), goal, r -> {
                    result.set(r);
                    started[1] = npcs.get().ticks() - started[0];
                })) {
                    throw new ScenarioAssertionError("the navigation service refused the order");
                }
                if (knock) {
                    npcs.get().everyTick(() -> npc.position().ifPresent(at -> {
                        if (!knocked[0] && at.x() >= knockX) {
                            knocked[0] = true;
                            Entity body = CitizensBootstrap.entity(npc).orElseThrow();
                            body.setVelocity(KNOCK);
                        }
                    }));
                }
            })
            .cleanup("remove the NPC (" + label + ")", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .await("the trip ends or the time is up (" + label + ")", TRIP_TICKS + 20,
                () -> result.get() != null || npcs.get().ticks() - started[0] >= TRIP_TICKS)
            .check(knock ? "one push of unknown kind, one walk back, then on to the goal"
                : "no push from its own walk, no walk back, it arrives", () -> {
                ScenarioNpcs n = npcs.get();
                long[] p = n.service().pushCounts();
                long returns = n.log().stream().filter(l -> l.text().contains(" returns ")).count();
                String line = String.format(Locale.ROOT, "%s: %s in %s, pushes %d (players %d, attacks %d), "
                        + "walks back %d, returned %d, teleported %d", label,
                    result.get() == null ? "no result" : result.get().getClass().getSimpleName(),
                    started[1] < 0 ? "-" : String.format(Locale.ROOT, "%.1f s", started[1] / 20.0), p[0], p[1], p[2],
                    returns, p[3], p[6]);
                report.add(line);
                scene.services().plugin().getLogger().info(NpcMovementReport.TAG + " " + "npc-push-return-hairpin | "
                    + line);
                List<String> failed = new ArrayList<>();
                if (!(result.get() instanceof NavigationResult.Arrived)) {
                    failed.add("the trip did not end at the goal: " + (result.get() == null ? "still walking after "
                        + TRIP_TICKS / 20 + " s" : result.get()));
                }
                if (knock) {
                    if (!knocked[0]) {
                        failed.add("never came up to the knock's place");
                    } else if (p[0] != 1) {
                        failed.add(p[0] + " pushes (expected the knock's one)");
                    }
                    if (returns != 1) {
                        failed.add(returns + " walks back (expected 1)");
                    }
                } else {
                    if (p[0] != 0) {
                        failed.add(p[0] + " pushes with nothing touching it");
                    }
                    if (returns != 0) {
                        failed.add(returns + " walks back");
                    }
                }
                if (n.metrics().total(Event.FELL) > 0) {
                    failed.add("it fell");
                }
                return failed.isEmpty() ? null : String.join("; ", failed) + n.tail(25);
            })
            .act("remove the NPC (" + label + ")", () -> npcs.get().stop());
    }
}
