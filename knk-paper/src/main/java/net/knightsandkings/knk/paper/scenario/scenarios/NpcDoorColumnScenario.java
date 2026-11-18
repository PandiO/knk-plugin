package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcMovementScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * A column of 12 through a one-block door with collision on (implementation plan 4c.4 item 7.2, link M7,
 * PandiO/knk-workspace#627): 12 singles in the doorway scene's west room (4c.5 item 5) walk to the east room at once,
 * with {@code crowd-awareness} and {@code local-legs-clearance} on, as in {@code npc-doorway-crowd}. Bodies push each
 * other now. "No jam longer than the queue needs" (4c.4 item 7.2): all arrive, no wait longer than the queue ahead
 * needs (M4's 3 s per NPC that went first), nobody still longer than 3 s outside a queue, none let go after 30 s, and
 * every stuck spell freed, each within {@link #MAX_SPELL_TICKS}. Stuck spells are reported, not required to be 0: with
 * bodies the first NPCs, which reach the door mouth before any queue forms, can both claim it and shove each other
 * against the frame (link M7's report block; the same run with collision off has none). Needs Citizens.
 */
public final class NpcDoorColumnScenario implements Scenario<ScenarioScene> {

    static final int MAX_TICKS = 40 * 20;
    /** A stuck spell at the door is freed within this (3 s), or the column jams. */
    static final int MAX_SPELL_TICKS = 3 * 20;

    private static final java.util.regex.Pattern FREED = java.util.regex.Pattern.compile(
        " freed at .* \\(\\d+ steps?, ([0-9.]+) s\\)");

    /** The longest a stuck spell took to be freed, in ticks, from the run log's "freed" lines. */
    static long longestFreedSpellTicks(ScenarioNpcs npcs) {
        long longest = 0;
        for (ScenarioNpcs.LogLine line : npcs.log()) {
            java.util.regex.Matcher m = FREED.matcher(line.text());
            if (m.find()) {
                longest = Math.max(longest, Math.round(Double.parseDouble(m.group(1)) * 20));
            }
        }
        return longest;
    }

    @Override
    public String name() {
        return "npc-door-column";
    }

    @Override
    public String description() {
        return "12 singles one way through a one-block door at once, collision on: no jam beyond the queue";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcMovementScene rooms = NpcMovementScene.doorway(scene);
        NpcMovementReport report = new NpcMovementReport(scene, name());
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<SingleTrips> trips = new AtomicReference<>();
        AtomicReference<String> built = new AtomicReference<>();
        long[] started = {0};
        int[] mostInDoor = {0};
        plan.act("paste the two rooms and the doorway", () -> built.set(rooms.build()))
            .act("start NPC navigation on Citizens, collision on", () -> npcs.set(ScenarioNpcs.start(scene,
                rooms.doorLoop(), NpcMovementReport.settings(true, true, true, false, false, true))))
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .act("12 singles in the west room walk to the east room, at once", () -> {
                List<NavPoint> starts = new ArrayList<>();
                List<NavPoint> goals = new ArrayList<>();
                for (double x : new double[] {3.5, 5.5, 7.5, 9.5}) {
                    for (double z : new double[] {4.5, 7.5, 10.5}) {
                        starts.add(rooms.feet(x, NpcMovementScene.FLAT, z));
                        goals.add(rooms.feet(24 - x, NpcMovementScene.FLAT, z));
                    }
                }
                report.phaseStarts(npcs.get());
                started[0] = npcs.get().ticks();
                trips.set(SingleTrips.send(npcs.get(), starts, goals));
                npcs.get().everyTick(() -> {
                    int inDoor = 0;
                    for (NpcMover mover : trips.get().movers()) {
                        NavPoint at = mover.position().orElse(null);
                        if (at != null && rooms.inDoorway(at)) {
                            inDoor++;
                        }
                    }
                    mostInDoor[0] = Math.max(mostInDoor[0], inDoor);
                });
            })
            .await("every trip ends or the time is up", MAX_TICKS + 20,
                () -> trips.get().allEnded() || npcs.get().ticks() - started[0] >= MAX_TICKS)
            .check("all 12 arrive, no jam beyond the queue, nobody still over 3 s, 0 stuck spells", () -> {
                List<String> failed = new ArrayList<>();
                if (!trips.get().allEnded() || trips.get().arrived() != trips.get().movers().size()) {
                    failed.add("arrived " + trips.get().arrivals() + " (" + trips.get().others() + ")");
                }
                net.knightsandkings.knk.core.npc.nav.crowd.CrowdControl c = npcs.get().service().crowd();
                if (c.worstWaitPerPass() > 3 * 20) {
                    failed.add(String.format(Locale.ROOT, "a wait of %.1f s per NPC ahead (at most 3)",
                        c.worstWaitPerPass() / 20.0));
                }
                if (c.overdue() != 0) {
                    failed.add(c.overdue() + " NPCs let go after 30 s in a queue");
                }
                var spells = npcs.get().metrics().spells();
                long notFreed = report.count(npcs.get(),
                    net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event.NOT_FREED);
                if (notFreed > 0 || spells.open() > 0) {
                    failed.add(notFreed + " stuck spells not freed, " + spells.open() + " open");
                }
                long longestSpell = longestFreedSpellTicks(npcs.get());
                if (longestSpell > MAX_SPELL_TICKS) {
                    failed.add(String.format(Locale.ROOT, "a stuck spell took %.1f s to free", longestSpell / 20.0));
                }
                if (npcs.get().longestStillTicks() > NpcMovementReport.MAX_STILL_TICKS) {
                    failed.add(String.format(Locale.ROOT, "still for %.1f s outside a queue (%s)",
                        npcs.get().longestStillTicks() / 20.0, npcs.get().longestStillWho()));
                }
                report.add("scene", built.get())
                    .add("arrived", trips.get().arrivals())
                    .add("others", trips.get().others())
                    .add("trips", trips.get().tripTimes())
                    .add("most in the doorway at once", mostInDoor[0])
                    .crowd(npcs.get())
                    .common(npcs.get(), rooms)
                    .log("12 singles one way through the door, collision on");
                return failed.isEmpty() ? null : String.join("; ", failed) + npcs.get().tail(40);
            });
    }
}
