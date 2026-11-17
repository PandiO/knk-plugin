package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.npc.citizens.CitizensBootstrap;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * Players pushing a guard on a wall walk (implementation plan 4c.4 items 7.2 and 7.3, link M7,
 * PandiO/knk-workspace#627), collision on: a wall walk 3 wide at floor 7, a parapet on its north side and a 7-block
 * drop into the courtyard on its south side; a guard stands on the middle cell (15.5 8 11.5) and a fake player (#540)
 * walks into it from the parapet side, towards the drop.
 * <ol>
 *     <li>Players never push (the default with collision on): the guard does not move and never falls.</li>
 *     <li>With {@code players-push-npcs}: the guard is pushed, the player is exempted as soon as the push goes towards
 *     the drop, the guard never passes the edge cell, never falls, and walks back to its post.</li>
 *     <li>Repeated pushes along the walk (with {@code players-push-npcs}): the third push exempts the player; the fourth
 *     moves the guard no more; the guard returns each time.</li>
 *     <li>An attack's knockback on a guard walking the wall walk (not protected, so the attack lands): the knockback
 *     stays, the guard walks back to its line and on, never falls.</li>
 * </ol>
 * Needs Citizens.
 */
public final class NpcGuardLedgePushScenario implements Scenario<ScenarioScene> {

    /** The wall walk's floor (local y); feet stand at {@link #FEET}. */
    static final int FLOOR = 7;
    static final int FEET = FLOOR + 1;
    /** The wall walk's cells: z 10..12 (12 is next to the drop), x 2..29; the parapet at z 9. */
    static final int NORTH = 10;
    static final int SOUTH = 12;
    static final double POST_X = 15.5;
    static final double POST_Z = 11.5;

    @Override
    public String name() {
        return "npc-guard-ledge-push";
    }

    @Override
    public String description() {
        return "A player pushes a guard on a wall walk towards the drop (players never push; players push), pushes it "
            + "again and again, and knocks a walking guard back: it holds or returns, never falls";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        ScenarioPlayer player = scene.player("ScnShover", p -> p.account(scene.fakeId(1)));
        List<String> report = new ArrayList<>();
        plan.act("build the wall walk over the courtyard", () -> build(scene))
            .act("a player joins", player::join)
            .await("the player is online", 200, player::online);
        towardsTheDrop(plan, scene, player, false, report);
        towardsTheDrop(plan, scene, player, true, report);
        repeatedPushes(plan, scene, player, report);
        knockback(plan, scene, player, report);
    }

    /** Logs a phase's report line at once (CI copies the lines into the job summary, failed phases included). */
    private static void log(ScenarioScene scene, List<String> report, String line) {
        report.add(line);
        scene.services().plugin().getLogger().info(NpcMovementReport.TAG + " npc-guard-ledge-push | " + line);
    }

    static void build(ScenarioScene scene) {
        for (int x = 0; x < ScenarioScene.SIZE; x++) {
            for (int z = 0; z < ScenarioScene.SIZE; z++) {
                scene.block(x, 0, z).setType(Material.STONE);
            }
        }
        for (int x = 2; x <= 29; x++) {
            for (int z = NORTH; z <= SOUTH; z++) {
                for (int y = 1; y <= FLOOR; y++) {
                    scene.block(x, y, z).setType(Material.STONE_BRICKS);
                }
            }
            scene.block(x, FEET, NORTH - 1).setType(Material.STONE_BRICK_WALL);
            scene.block(x, FEET + 1, NORTH - 1).setType(Material.STONE_BRICK_WALL);
        }
    }

    /** The wall walk's middle line as floor points (the road the NPCs plan on). */
    static List<NavPoint> line(ScenarioScene scene) {
        return List.of(ScenarioPushes.feet(scene, 3.5, FLOOR, POST_Z), ScenarioPushes.feet(scene, 28.5, FLOOR, POST_Z));
    }

    static NavigationSettings settings(boolean playersPush) {
        return NavigationSettings.builder().global(NavigationSettings.Overrides.of(NavigationSettings.COLLIDABLE, true)
            .with(NavigationSettings.PLAYERS_PUSH_NPCS, playersPush)).build();
    }

    /** Watches one NPC every tick: how far it got from a point, its lowest feet and its furthest z. */
    private static final class Watch {
        double moved;
        double lowest = Double.MAX_VALUE;
        double furthestZ = -Double.MAX_VALUE;
        BukkitTask task;

        void start(ScenarioScene scene, NpcMover mover, NavPoint from) {
            task = Bukkit.getScheduler().runTaskTimer(scene.services().plugin(), () -> mover.position().ifPresent(at -> {
                moved = Math.max(moved, at.horizontalDistance(from));
                lowest = Math.min(lowest, at.y());
                furthestZ = Math.max(furthestZ, at.z());
            }), 1L, 1L);
        }

        void stop() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }
    }

    private static void towardsTheDrop(ScenarioPlan plan, ScenarioScene scene, ScenarioPlayer player,
                                       boolean playersPush, List<String> report) {
        String label = playersPush ? "players push" : "players never push";
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        Watch watch = new Watch();
        double[] from = {POST_X, FEET, NORTH + 0.4};
        double[] to = {POST_X, FEET, SOUTH + 1.4};
        NavPoint post = ScenarioPushes.feet(scene, POST_X, FEET, POST_Z);
        double edge = ScenarioPushes.feet(scene, 0, FEET, SOUTH + 1).z(); // the drop's first cell begins here
        double feetY = post.y();
        plan.act("start a guard on the wall walk (" + label + ")", () -> {
                npcs.set(ScenarioNpcs.start(scene, line(scene), settings(playersPush)));
                npcs.get().spawn(post);
            })
            .cleanup("remove the guard (" + label + ")", () -> {
                watch.stop();
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .waitTicks(20)
            .act("the player walks into the guard towards the drop (" + label + ")", () -> {
                watch.start(scene, npcs.get().movers().get(0), post);
                ScenarioPushes.sweep(scene, player, from, to);
            })
            .waitTicks(ScenarioPushes.ticks(from, to))
            .act("the player steps off the wall walk", () -> player.teleport(5.5, FEET, NORTH + 0.5))
            .waitTicks(120)
            .check(playersPush ? "pushed, the player exempted at the edge, never past the edge cell, never fell, back "
                + "at its post" : "the guard did not move and never fell", () -> {
                watch.stop();
                NpcMover guard = npcs.get().movers().get(0);
                NavPoint at = guard.position().orElse(null);
                long[] p = npcs.get().service().pushCounts();
                log(scene, report, String.format(Locale.ROOT, "%s: moved %.2f, lowest feet %+.2f, furthest %.2f before the "
                        + "drop, pushes %d, exempted %d, returned %d, back %.2f from its post", label, watch.moved,
                    watch.lowest - feetY, edge - watch.furthestZ, p[0], p[4], p[3],
                    at == null ? Double.NaN : at.horizontalDistance(post)));
                List<String> failed = new ArrayList<>();
                if (watch.lowest < feetY - 0.5 || npcs.get().metrics().total(Event.FELL) > 0) {
                    failed.add("the guard fell (lowest feet " + ScenarioPushes.blocks(watch.lowest - feetY) + ")");
                }
                if (!playersPush) {
                    if (watch.moved > 0.1) {
                        failed.add("the player moved the guard " + ScenarioPushes.blocks(watch.moved));
                    }
                } else {
                    if (p[0] < 1) {
                        failed.add("no push seen (moved " + ScenarioPushes.blocks(watch.moved) + ")");
                    }
                    if (p[4] < 1) {
                        failed.add("the player was not exempted");
                    }
                    if (watch.furthestZ > edge - 0.3) {
                        failed.add("the guard's centre came within 0.3 of the drop ("
                            + ScenarioPushes.blocks(edge - watch.furthestZ) + ")");
                    }
                    if (at == null || at.horizontalDistance(post) > 0.5) {
                        failed.add("not back at its post: " + (at == null ? "gone"
                            : ScenarioPushes.blocks(at.horizontalDistance(post)) + " away") + npcs.get().tail(15));
                    }
                }
                return failed.isEmpty() ? null : String.join("; ", failed) + npcs.get().tail(20);
            })
            .act("remove the guard (" + label + ")", () -> npcs.get().stop());
    }

    private static void repeatedPushes(ScenarioPlan plan, ScenarioScene scene, ScenarioPlayer player,
                                       List<String> report) {
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        NavPoint post = ScenarioPushes.feet(scene, POST_X, FEET, POST_Z);
        double[] from = {POST_X - 2.5, FEET, POST_Z};
        double[] to = {POST_X + 2.0, FEET, POST_Z};
        double[] lastMoved = {0};
        List<String> rounds = new ArrayList<>();
        plan.act("start a guard on the wall walk (repeated pushes)", () -> {
                npcs.set(ScenarioNpcs.start(scene, line(scene), settings(true)));
                npcs.get().spawn(post);
            })
            .cleanup("remove the guard (repeated pushes)", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .waitTicks(20);
        for (int round = 1; round <= 4; round++) {
            int r = round;
            Watch watch = new Watch();
            plan.act("push " + round + " along the walk", () -> {
                    watch.start(scene, npcs.get().movers().get(0), npcs.get().movers().get(0).position().orElse(post));
                    ScenarioPushes.sweep(scene, player, from, to);
                })
                .waitTicks(ScenarioPushes.ticks(from, to))
                .act("the player steps aside after push " + round, () -> player.teleport(5.5, FEET, NORTH + 0.5))
                .waitTicks(30) // three pushes within push-exempt-window-ticks (10 s)
                .act("note push " + round, () -> {
                    watch.stop();
                    lastMoved[0] = watch.moved;
                    long[] p = npcs.get().service().pushCounts();
                    rounds.add(String.format(Locale.ROOT, "%d: moved %.2f, exempted %d", r, watch.moved, p[4]));
                });
        }
        plan.check("the third push exempts the player, the fourth moves the guard no more, it is back each time", () -> {
            long[] p = npcs.get().service().pushCounts();
            NavPoint at = npcs.get().movers().get(0).position().orElse(null);
            log(scene, report, "repeated pushes: " + String.join(", ", rounds) + String.format(Locale.ROOT,
                "; pushes %d, returned %d, back %.2f from its post", p[0], p[3],
                at == null ? Double.NaN : at.horizontalDistance(post)));
            List<String> failed = new ArrayList<>();
            if (p[4] != 1) {
                failed.add(p[4] + " exemptions (expected 1)");
            }
            if (lastMoved[0] > 0.1) {
                failed.add("the fourth push moved the guard " + ScenarioPushes.blocks(lastMoved[0]));
            }
            if (p[0] < 3) {
                failed.add("only " + p[0] + " pushes seen");
            }
            if (at == null || at.horizontalDistance(post) > 0.5) {
                failed.add("not back at its post");
            }
            if (npcs.get().metrics().total(Event.FELL) > 0) {
                failed.add("it fell");
            }
            return failed.isEmpty() ? null : String.join("; ", failed) + npcs.get().tail(30);
        }).act("remove the guard (repeated pushes)", () -> npcs.get().stop());
    }

    private static void knockback(ScenarioPlan plan, ScenarioScene scene, ScenarioPlayer player, List<String> report) {
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        Watch watch = new Watch();
        NavPoint west = ScenarioPushes.feet(scene, 4.5, FEET, POST_Z);
        NavPoint east = ScenarioPushes.feet(scene, 27.5, FEET, POST_Z);
        double feetY = west.y();
        double lineZ = west.z();
        double[] off = {0};
        double[] backTicks = {-1};
        long[] hitTick = {0};
        plan.act("start a guard walking the wall walk, not protected", () -> {
                npcs.set(ScenarioNpcs.start(scene, line(scene), settings(false)));
                NpcMover guard = npcs.get().spawn(west);
                CitizensBootstrap.setProtected(guard, false);
                npcs.get().patrol(guard, east, west);
            })
            .cleanup("remove the walking guard", () -> {
                watch.stop();
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .waitTicks(60)
            .act("the player hits the guard from the parapet side", () -> {
                NpcMover guard = npcs.get().movers().get(0);
                NavPoint at = guard.position().orElseThrow();
                watch.start(scene, guard, at);
                player.teleport(at.x() - scene.location(0, 0, 0).getX(), FEET,
                    at.z() - scene.location(0, 0, 0).getZ() - 1.4);
                Entity body = CitizensBootstrap.entity(guard).orElseThrow();
                player.player().attack(body);
                hitTick[0] = npcs.get().ticks();
                npcs.get().everyTick(() -> guard.position().ifPresent(p -> {
                    double d = Math.abs(p.z() - lineZ);
                    off[0] = Math.max(off[0], d);
                    if (backTicks[0] < 0 && off[0] > 0.3 && d < 0.4) {
                        backTicks[0] = npcs.get().ticks() - hitTick[0];
                    }
                }));
            })
            .waitTicks(10)
            .act("the player steps off the wall walk", () -> player.teleport(5.5, FEET, NORTH - 2.5))
            .waitTicks(160)
            .check("knocked off its line, it walked back and on, never fell", () -> {
                watch.stop();
                long[] p = npcs.get().service().pushCounts();
                log(scene, report, String.format(Locale.ROOT, "knockback: off its line %.2f, back on it after %s, lowest feet "
                        + "%+.2f, pushes %d (attacks %d), returned %d, arrivals %d", off[0],
                    backTicks[0] < 0 ? "never" : String.format(Locale.ROOT, "%.1f s", backTicks[0] / 20.0),
                    watch.lowest - feetY, p[0], p[2], p[3], npcs.get().metrics().total(Event.ARRIVED)));
                List<String> failed = new ArrayList<>();
                if (watch.lowest < feetY - 0.5 || npcs.get().metrics().total(Event.FELL) > 0) {
                    failed.add("it fell");
                }
                if (off[0] < 0.3) {
                    failed.add("the attack did not knock it off its line (" + ScenarioPushes.blocks(off[0]) + ")");
                } else if (backTicks[0] < 0) {
                    failed.add("not back on its line" + npcs.get().tail(15));
                }
                if (p[2] < 1) {
                    failed.add("the knockback was not seen as an attack's push (pushes " + p[0] + ")");
                }
                return failed.isEmpty() ? null : String.join("; ", failed) + npcs.get().tail(20);
            })
            .act("remove the walking guard", () -> npcs.get().stop());
    }
}
