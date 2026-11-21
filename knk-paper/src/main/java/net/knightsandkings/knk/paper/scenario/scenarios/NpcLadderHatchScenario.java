package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.navigation.shape.NavGeometry;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcLadderHatchScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.scenario.SizedScene;

/**
 * Link H1 (PandiO/knk-workspace#779): {@code npc-ladder-hatch}, one NPC's off-road leg from a road to a tower's roof,
 * whose only way up is a ladder and a roof hatch above it ({@link NpcLadderHatchScene}). Four trips, each with a new
 * NPC from the road:
 * <ol>
 *   <li>the hatch open, an access profile with {@code open-doors}: the NPC climbs through and arrives on the roof;</li>
 *   <li>the hatch open, a profile without {@code open-doors}: the same (an open hatch is no door to open);</li>
 *   <li>the hatch closed, with {@code open-doors}: the NPC opens the hatch on its way up and arrives;</li>
 *   <li>the hatch closed, without {@code open-doors}: the hatch blocks; no walk is planned through it, the NPC never
 *       gets onto the roof and the hatch stays closed;</li>
 *   <li>down (PandiO/knk-workspace#956), from the roof to the road with {@code open-doors}, the hatch open: the NPC
 *       goes down the ladder and arrives, with no stuck spell and no fall;</li>
 *   <li>down, the hatch closed: the NPC opens it from above, goes down and arrives the same way.</li>
 * </ol>
 * The arriving trips also fail on a stuck spell (the NPC stalled at the ladder, the issue's symptom) and when the leg
 * was not planned with the walk search. Settings: the code defaults with {@code local-legs-clearance} and
 * {@code corridor-steering} on, as {@link NpcOffRoadScenario}.
 */
public final class NpcLadderHatchScenario implements Scenario<ScenarioScene>, SizedScene {

    static final int WALK_TICKS = 60 * 20;
    /** The blocked trip: long enough for the walk search, its fallback and a stuck spell or two. */
    static final int BLOCKED_TICKS = 40 * 20;

    static final NpcAccessProfile NO_DOORS = new NpcAccessProfile("no-doors", false, false, Set.of(), Set.of());

    private record Trip(String what, boolean hatchOpen, NpcAccessProfile profile, boolean arrives, boolean down) {
        Trip(String what, boolean hatchOpen, NpcAccessProfile profile, boolean arrives) {
            this(what, hatchOpen, profile, arrives, false);
        }
    }

    private static final List<Trip> TRIPS = List.of(
        new Trip("hatch open, open-doors", true, NpcAccessProfile.DEFAULT, true),
        new Trip("hatch open, no open-doors", true, NO_DOORS, true),
        new Trip("hatch closed, open-doors", false, NpcAccessProfile.DEFAULT, true),
        new Trip("hatch closed, no open-doors", false, NO_DOORS, false),
        new Trip("down, hatch open, open-doors", true, NpcAccessProfile.DEFAULT, true, true),
        new Trip("down, hatch closed, open-doors", false, NpcAccessProfile.DEFAULT, true, true));

    @Override
    public String name() {
        return "npc-ladder-hatch";
    }

    @Override
    public String description() {
        return "An off-road leg up a tower's ladder through its roof hatch, open and closed, with and without "
            + "open-doors, and down it from the roof";
    }

    @Override
    public ScenarioScene.Box box() {
        return NpcLadderHatchScene.BOX;
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcLadderHatchScene tower = NpcLadderHatchScene.in(scene);
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        plan.act("build the tower", () -> tower.build(true))
            .act("start NPC navigation on Citizens",
                () -> npcs.set(ScenarioNpcs.start(scene, tower.loop(), NpcOffRoadScenario.settings())))
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            });
        for (Trip trip : TRIPS) {
            trip(plan, tower, npcs, trip);
        }
    }

    private void trip(ScenarioPlan plan, NpcLadderHatchScene tower, AtomicReference<ScenarioNpcs> npcs, Trip trip) {
        AtomicReference<NavigationResult> result = new AtomicReference<>();
        AtomicReference<NpcMover> npc = new AtomicReference<>();
        AtomicBoolean everOnRoof = new AtomicBoolean();
        AtomicBoolean everOpened = new AtomicBoolean();
        long[] t = {0, -1};
        long[] before = new long[3];
        int ticks = trip.arrives() ? WALK_TICKS : BLOCKED_TICKS;
        NavPoint from = trip.down() ? tower.goal() : tower.start();
        NavPoint to = trip.down() ? tower.start() : tower.goal();
        plan.act((trip.down() ? "one NPC walks from the roof to the road: " : "one NPC walks to the roof: ")
                + trip.what(), () -> {
                tower.setHatch(trip.hatchOpen());
                before[0] = stuckSpells(npcs.get());
                before[1] = npcs.get().service().offRoadWalks();
                before[2] = npcs.get().metrics().total(Event.FELL);
                npc.set(npcs.get().spawn(from, trip.profile()));
                t[0] = npcs.get().ticks();
                npcs.get().everyTick(() -> {
                    NpcMover m = npc.get();
                    NavPoint at = m == null ? null : m.position().orElse(null);
                    if (at != null && tower.onTheRoof(at)) {
                        everOnRoof.set(true);
                    }
                    if (!trip.hatchOpen() && tower.hatchOpen()) {
                        everOpened.set(true);
                    }
                });
                if (!npcs.get().service().navigate(npc.get().id(), to, r -> {
                    result.set(r);
                    t[1] = npcs.get().ticks() - t[0];
                })) {
                    throw new ScenarioAssertionError("the navigation service refused the order");
                }
            })
            .await("the trip ends or the time is up", ticks + 20,
                () -> result.get() != null || npcs.get().ticks() - t[0] >= ticks)
            .check(trip.down() ? "the NPC went down the hatch and the ladder to the road, no stuck spell, no fall"
                : trip.arrives() ? "the NPC climbed through the hatch onto the roof"
                : "the closed hatch kept the NPC off the roof", () -> {
                List<String> failures = new ArrayList<>();
                ScenarioNpcs n = npcs.get();
                long stuck = stuckSpells(n) - before[0];
                long walks = n.service().offRoadWalks() - before[1];
                NavPoint at = npc.get().position().orElse(null);
                report(n, trip, result.get(), t[1], stuck, walks, at, everOpened.get());
                if (trip.down()) {
                    long fell = n.metrics().total(Event.FELL) - before[2];
                    if (!(result.get() instanceof NavigationResult.Arrived)) {
                        failures.add("the NPC did not arrive: " + (result.get() == null ? "still walking after "
                            + ticks / 20 + " s at " + (at == null ? "-" : NavGeometry.block(at)) : result.get()));
                    }
                    if (at == null || at.horizontalDistance(to) > 1.5 || Math.abs(at.y() - to.y()) > 1) {
                        failures.add("the NPC is not at the road's end: " + (at == null ? "-" : NavGeometry.block(at)));
                    }
                    if (stuck > 0) {
                        failures.add(stuck + " stuck spells on the way");
                    }
                    if (fell > 0) {
                        failures.add(fell + " falls");
                    }
                    if (walks == 0) {
                        failures.add("the leg from the roof was not planned with the walk search");
                    }
                    if (!trip.hatchOpen() && !everOpened.get()) {
                        failures.add("the hatch was never opened");
                    }
                } else if (trip.arrives()) {
                    if (!(result.get() instanceof NavigationResult.Arrived)) {
                        failures.add("the NPC did not arrive: " + (result.get() == null ? "still walking after "
                            + ticks / 20 + " s at " + (at == null ? "-" : NavGeometry.block(at)) : result.get()));
                    }
                    if (at == null || !tower.onTheRoof(at)) {
                        failures.add("the NPC is not on the roof: " + (at == null ? "-" : NavGeometry.block(at)));
                    }
                    if (stuck > 0) {
                        failures.add(stuck + " stuck spells on the way");
                    }
                    if (walks == 0) {
                        failures.add("the leg to the roof was not planned with the walk search");
                    }
                    if (!trip.hatchOpen() && !everOpened.get()) {
                        failures.add("the hatch was never opened");
                    }
                } else {
                    // the order's own result is not judged: when no walk is found the leg falls back to hops, and the
                    // hops end as "arrived" under the goal (PandiO/knk-workspace#848, as before this link)
                    if (walks > 0) {
                        failures.add("a walk was planned through the closed hatch");
                    }
                    if (everOnRoof.get()) {
                        failures.add("the NPC got onto the roof");
                    }
                    if (everOpened.get()) {
                        failures.add("the hatch was opened by an NPC that may not open doors");
                    }
                }
                n.remove(new ArrayList<>(n.movers()));
                return failures.isEmpty() ? null : String.join("; ", failures) + n.tail(20);
            });
    }

    private static long stuckSpells(ScenarioNpcs npcs) {
        return npcs.metrics().total(Event.STUCK_ENGINE) + npcs.metrics().total(Event.STUCK_NO_PROGRESS);
    }

    private void report(ScenarioNpcs npcs, Trip trip, NavigationResult result, long took, long stuck, long walks,
                        NavPoint at, boolean opened) {
        org.bukkit.Bukkit.getLogger().info(String.format(Locale.ROOT, "KNK-NPC-REPORT %s %s: %s; off-road walks %d; "
                + "stuck spells %d; hatch opened %s; ends at %s; jumps held back %s", name(), trip.what(),
            result == null ? "still walking" : result.getClass().getSimpleName() + String.format(Locale.ROOT,
                " after %.1f s", took / 20.0), walks, stuck, opened, at == null ? "-" : NavGeometry.block(at),
            npcs.movers().isEmpty() ? "-" : npcs.movers().get(0).stepHopsHeld()));
    }
}
