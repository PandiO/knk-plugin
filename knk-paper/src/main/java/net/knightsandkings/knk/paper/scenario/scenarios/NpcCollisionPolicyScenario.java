package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioPlayer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * Who pushes whom on Paper 26.3 with Citizens (implementation plan 4c.4 item 7.1, link M7, PandiO/knk-workspace#627):
 * the scenario that chose K&K's collision policy, kept as its regression. A fake player walks straight through a
 * standing NPC, and two NPCs start 0.2 apart, with
 * <ol>
 *     <li>collision off: the player passes through, the NPCs stay overlapped;</li>
 *     <li>collision on, players never push (the default with collision on): the player passes through, the NPC does
 *     not move, the two NPCs push each other apart;</li>
 *     <li>collision on with {@code players-push-npcs}: the player carries the NPC (the reference, push return off).</li>
 * </ol>
 * The policy (Paper's per-entity collidable exemptions) was chosen on the first runs, against scoreboard teams:
 * {@code FOR_OTHER_TEAMS} kept the player out too, {@code FOR_OWN_TEAM} (vanilla {@code pushOwnTeam}) did not, and
 * Citizens put the NPCs on no server team (link M7's report block). Needs Citizens.
 */
public final class NpcCollisionPolicyScenario implements Scenario<ScenarioScene> {

    private enum Mode {
        OFF(false, false), PLAYERS_NEVER(true, false), PLAYERS_PUSH(true, true);

        final boolean collidable;
        final boolean playersPush;

        Mode(boolean collidable, boolean playersPush) {
            this.collidable = collidable;
            this.playersPush = playersPush;
        }

        String label() {
            return name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    @Override
    public String name() {
        return "npc-collision-policy";
    }

    @Override
    public String description() {
        return "A player walks through a standing NPC and two NPCs overlap: collision off, on (players never push), on "
            + "with players-push-npcs";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        ScenarioPlayer player = scene.player("ScnPusher", p -> p.account(scene.fakeId(1)));
        List<String> report = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        plan.act("lay a floor", () -> {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        scene.block(x, 0, z).setType(Material.STONE);
                    }
                }
            })
            .act("a player joins", player::join)
            .await("the player is online", 200, player::online);
        for (Mode mode : Mode.values()) {
            AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
            AtomicReference<BukkitTask> watch = new AtomicReference<>();
            double[] moved = {0};
            double[] from = {5.5, 1, 8.5};
            double[] to = {11.5, 1, 8.5};
            plan.act("start NPCs: collision " + mode.label(), () -> {
                    npcs.set(ScenarioNpcs.start(scene, List.of(ScenarioPushes.feet(scene, 1.5, 1, 1.5),
                        ScenarioPushes.feet(scene, 2.5, 1, 1.5)), NavigationSettings.builder()
                        .global(NavigationSettings.Overrides.of(NavigationSettings.COLLIDABLE, mode.collidable)
                            .with(NavigationSettings.PLAYERS_PUSH_NPCS, mode.playersPush)
                            .with(NavigationSettings.PUSH_RETURN, false)).build()));
                    npcs.get().spawn(ScenarioPushes.feet(scene, 8.5, 1, 8.5));
                    npcs.get().spawn(ScenarioPushes.feet(scene, 8.5, 1, 12.5));
                    npcs.get().spawn(ScenarioPushes.feet(scene, 8.7, 1, 12.5));
                    player.teleport(from[0], from[1], from[2]);
                })
                .cleanup("remove the NPCs (" + mode.label() + ")", () -> {
                    if (watch.get() != null) {
                        watch.get().cancel();
                    }
                    if (npcs.get() != null) {
                        npcs.get().stop();
                    }
                })
                .waitTicks(10)
                .act("the player walks through the standing NPC (" + mode.label() + ")", () -> {
                    NpcMover standing = npcs.get().movers().get(0);
                    NavPoint start = standing.position().orElseThrow();
                    watch.set(Bukkit.getScheduler().runTaskTimer(scene.services().plugin(), () ->
                        standing.position().ifPresent(at -> moved[0] = Math.max(moved[0],
                            at.horizontalDistance(start))), 1L, 1L));
                    ScenarioPushes.sweep(scene, player, from, to);
                })
                .waitTicks(ScenarioPushes.ticks(from, to) + 20)
                .act("measure (" + mode.label() + ")", () -> {
                    watch.get().cancel();
                    List<NpcMover> m = npcs.get().movers();
                    NavPoint b = m.get(1).position().orElseThrow();
                    NavPoint c = m.get(2).position().orElseThrow();
                    double gap = b.horizontalDistance(c);
                    report.add(mode.label() + ": the player moved the NPC " + ScenarioPushes.blocks(moved[0])
                        + ", two NPCs 0.20 apart are " + ScenarioPushes.blocks(gap) + " apart");
                    boolean playersMove = mode == Mode.PLAYERS_PUSH;
                    if (playersMove ? moved[0] < 0.5 : moved[0] > 0.05) {
                        failed.add(mode.label() + ": the player moved the NPC " + ScenarioPushes.blocks(moved[0])
                            + (playersMove ? " (expected a push)" : " (expected none)"));
                    }
                    if (mode.collidable ? gap < 0.5 : gap > 0.3) {
                        failed.add(mode.label() + ": two NPCs " + ScenarioPushes.blocks(gap) + " apart"
                            + (mode.collidable ? " (expected them pushed apart)" : " (expected them overlapped)"));
                    }
                    npcs.get().stop();
                });
        }
        plan.check("players move NPCs only with players-push-npcs; NPCs push each other with collision on", () -> {
            scene.services().plugin().getLogger().info(net.knightsandkings.knk.paper.scenario.scenarios.NpcMovementReport.TAG
                + " " + name() + " | " + String.join(" | ", report));
            return failed.isEmpty() ? null : String.join("; ", failed);
        });
    }
}
