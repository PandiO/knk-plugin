package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcMovementScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * Plan 4c.5 item 6 (link M1, PandiO/knk-workspace#621; asserts since link M5, PandiO/knk-workspace#625): a 15 × 15
 * plaza with four roads and a well off-centre on the chord; the road graph's line runs to the plaza junction in the
 * middle and out again. With {@code plaza-lines} on, 3 singles cross from the west road to the north road: all arrive,
 * each walks within 5 % of the shortest line from where it entered the plaza to where it left that keeps
 * {@code wall-clearance} from the well (the chord bent round the well, which stands on it), passes the well with the
 * clearance (less the engine's own walking slack) and never near the junction. Then a group of 7 in ranks of 3
 * ({@code group-formation-keeping: elastic}) crosses the same way: the leader arrives within 10 % of the shortest line
 * round the well with the group's clearance (its formation's half width plus {@code wall-clearance}), not through the
 * junction, the group keeps its width on the open plaza (0 folds), no member comes nearer the well than the hard
 * minimum 0.35 (its outer lanes keep 0.8 nominally; with collision on bodies push), and corridor
 * shaping in that phase stays within 0.03 ms per NPC per tick (the singles' phase, the server's first plaza shaping,
 * is reported, not judged). Reports the walked lengths against the plain chord too
 * (M1 measured +40 %). Needs Citizens.
 */
public final class NpcPlazaLineScenario implements Scenario<ScenarioScene> {

    static final int MAX_TICKS = 25 * 20;
    static final int GROUP_TICKS = 40 * 20;
    /** Within this share over the shortest clear line (plan 4c.4 item 5.2). */
    static final double OVER = 0.05;
    /** The engine walks a waypoint list within {@code waypoint-margin} of each bend: this much nearer the well is allowed. */
    static final double WALK_SLACK = 0.25;
    /** A single never nearer the plaza junction than this (the way round the well passes it at about 2.7 blocks). */
    static final double FROM_JUNCTION = 2.0;
    /**
     * A group's leader never nearer than this: the group's line keeps its formation's half width plus
     * {@code wall-clearance} (1.8) from the well, and the way round it passes about 1.7 from the junction.
     */
    static final double GROUP_FROM_JUNCTION = 1.0;
    /** A group's leader within this share over the shortest line round the well with the group's clearance. */
    static final double GROUP_OVER = 0.10;
    /**
     * A group member never nearer the well than this ({@code wall-clearance}'s hard minimum): the group's line keeps
     * 1.8 from it, its outer lanes 0.8 nominally, and with collision on bodies push each other aside.
     */
    static final double GROUP_FROM_WELL = 0.35;
    /** K&K's corridor shaping over the whole run, ms per NPC per tick (NAV-28's budget). */
    static final double BUDGET_MS = 0.03;

    private final boolean collisionOn;

    public NpcPlazaLineScenario(boolean collisionOn) {
        this.collisionOn = collisionOn;
    }

    @Override
    public String name() {
        return NpcMovementReport.name("npc-plaza-line", collisionOn);
    }

    @Override
    public String description() {
        return "Plaza lines: 3 singles and a group of 7 cross a plaza on the chord round the well (collision "
            + (collisionOn ? "on" : "off") + ")";
    }

    /** One NPC's way across the plaza. */
    private static final class Crossing {
        NavPoint entered;
        NavPoint left;
        NavPoint last;
        double walked;
        double closestToWell = Double.MAX_VALUE;
        double closestToJunction = Double.MAX_VALUE;

        void see(NavPoint at, NpcMovementScene plaza) {
            if (entered == null) {
                entered = at;
            } else {
                walked += last.horizontalDistance(at);
            }
            last = at;
            left = at;
            closestToWell = Math.min(closestToWell, plaza.fromWell(at));
            closestToJunction = Math.min(closestToJunction, plaza.fromJunction(at));
        }
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcMovementScene plaza = NpcMovementScene.plaza(scene);
        NpcMovementReport report = new NpcMovementReport(scene, name());
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<SingleTrips> trips = new AtomicReference<>();
        AtomicReference<String> built = new AtomicReference<>();
        AtomicReference<NavGroup> group = new AtomicReference<>();
        AtomicReference<NavigationResult> result = new AtomicReference<>();
        List<NpcMover> members = new ArrayList<>();
        Map<Integer, Crossing> crossings = new HashMap<>();
        Map<Integer, Crossing> groupCrossings = new HashMap<>();
        double clearance = NavigationSettings.WALL_CLEARANCE.defaultValue();
        long[] started = {0};
        long[] took = {-1};
        long[] costBase = {0, 0}; // corridor nanoseconds and NPC ticks when the group phase starts
        plan.act("paste the plaza", () -> built.set(plaza.build()))
            .act("start NPC navigation on Citizens with plaza lines on", () -> {
                npcs.set(ScenarioNpcs.start(scene, plaza.plazaLoop(),
                    NpcMovementReport.settings(collisionOn, false, false, true, true)));
                npcs.get().plazas(List.of(plaza.plazaJunction()));
            })
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .act("3 singles cross from the west road to the north road", () -> {
                List<NavPoint> starts = List.of(plaza.feet(1.5, NpcMovementScene.FLAT, 15.5),
                    plaza.feet(3.0, NpcMovementScene.FLAT, 14.5), plaza.feet(4.5, NpcMovementScene.FLAT, 16.5));
                List<NavPoint> goals = List.of(plaza.feet(15.5, NpcMovementScene.FLAT, 1.5),
                    plaza.feet(14.5, NpcMovementScene.FLAT, 2.5), plaza.feet(16.5, NpcMovementScene.FLAT, 3.5));
                report.phaseStarts(npcs.get());
                started[0] = npcs.get().ticks();
                trips.set(SingleTrips.send(npcs.get(), starts, goals));
                npcs.get().everyTick(() -> {
                    watch(trips.get().movers(), crossings, plaza);
                    watch(members, groupCrossings, plaza);
                });
            })
            .await("every trip ends or the time is up", MAX_TICKS + 20,
                () -> trips.get().allEnded() || npcs.get().ticks() - started[0] >= MAX_TICKS)
            .check("the singles cross on the chord round the well, with the clearance, never near the junction (M5)", () -> {
                List<String> failed = new ArrayList<>();
                if (!trips.get().allEnded() || trips.get().arrived() != trips.get().movers().size()) {
                    failed.add("arrived " + trips.get().arrivals() + " (" + trips.get().others() + ")");
                }
                StringBuilder lines = new StringBuilder();
                double worst = 0;
                double worstChord = 0;
                double closest = Double.MAX_VALUE;
                for (NpcMover mover : trips.get().movers()) {
                    Crossing c = crossings.get(mover.id());
                    if (c == null || c.entered == null) {
                        failed.add(mover.name() + " never on the plaza");
                        continue;
                    }
                    double chord = c.entered.horizontalDistance(c.left);
                    double clear = plaza.clearChord(c.entered, c.left, clearance);
                    double overChord = chord < 1e-6 ? 0 : (c.walked / chord - 1) * 100;
                    double overClear = clear < 1e-6 ? 0 : (c.walked / clear - 1) * 100;
                    worst = Math.max(worst, overClear);
                    worstChord = Math.max(worstChord, overChord);
                    closest = Math.min(closest, c.closestToWell);
                    lines.append(lines.isEmpty() ? "" : "; ").append(String.format(Locale.ROOT,
                        "%s walked %.1f vs clear line %.1f (%+.0f %%), chord %.1f (%+.0f %%), well %.2f, junction %.1f",
                        mover.name(), c.walked, clear, overClear, chord, overChord, c.closestToWell,
                        c.closestToJunction));
                    if (c.walked > clear * (1 + OVER)) {
                        failed.add(String.format(Locale.ROOT, "%s walked %.1f, over 5 %% above the clear line %.1f",
                            mover.name(), c.walked, clear));
                    }
                    if (c.closestToWell < clearance - WALK_SLACK) {
                        failed.add(String.format(Locale.ROOT, "%s came %.2f from the well", mover.name(),
                            c.closestToWell));
                    }
                    if (c.closestToJunction < FROM_JUNCTION) {
                        failed.add(String.format(Locale.ROOT, "%s passed %.1f from the junction", mover.name(),
                            c.closestToJunction));
                    }
                }
                if (npcs.get().service().corridors().plazaLines() < 1) {
                    failed.add("no plaza line was placed");
                }
                report.add("scene", built.get())
                    .add("arrived", trips.get().arrivals())
                    .add("others", trips.get().others())
                    .add("on the plaza", lines.isEmpty() ? "-" : lines.toString())
                    .add("most over the clear line", "%.0f %%", worst)
                    .add("most over the chord", "%.0f %%", worstChord)
                    .add("closest to the well", closest == Double.MAX_VALUE ? "-" : String.format(Locale.ROOT, "%.2f blocks", closest))
                    .plazaLines(npcs.get())
                    .common(npcs.get(), plaza)
                    .log("3 singles west road to north road");
                return failed.isEmpty() ? null : String.join("; ", failed);
            })
            .act("the singles leave; a group of 7 in ranks of 3 crosses the same way", () -> {
                npcs.get().remove(trips.get().movers());
                for (int i = 0; i < 7; i++) {
                    members.add(npcs.get().spawn(i == 0 ? plaza.feet(5.5, NpcMovementScene.FLAT, 15.5)
                        : plaza.feet(i <= 3 ? 4.0 : 2.5, NpcMovementScene.FLAT, 14.5 + (i - 1) % 3)));
                }
                report.phaseStarts(npcs.get());
                started[0] = npcs.get().ticks();
                costBase[0] = npcs.get().service().corridors().nanos();
                costBase[1] = npcs.get().npcTicks();
                group.set(npcs.get().group(members, Formation.Type.RANKS));
                group.get().navigate(plaza.feet(15.5, NpcMovementScene.FLAT, 1.5), r -> {
                    result.set(r);
                    took[0] = npcs.get().ticks() - started[0];
                });
            })
            .await("the group's trip ends or the time is up", GROUP_TICKS + 20,
                () -> result.get() != null || npcs.get().ticks() - started[0] >= GROUP_TICKS)
            .check("the group takes the plaza line, keeps its width and passes the well with the clearance (M5)", () -> {
                List<String> failed = new ArrayList<>();
                String group3 = report.groupFailures(npcs.get(), result.get(), 0);
                if (group3 != null) {
                    failed.add(group3);
                }
                Crossing lead = groupCrossings.get(members.get(0).id());
                double closestWell = Double.MAX_VALUE;
                for (Crossing c : groupCrossings.values()) {
                    closestWell = Math.min(closestWell, c.closestToWell);
                }
                double groupClear = group.get().plazaClearance();
                double leadClear = lead == null ? Double.NaN : plaza.clearChord(lead.entered, lead.left, groupClear);
                if (lead == null) {
                    failed.add("the leader never crossed the plaza");
                } else {
                    if (lead.closestToJunction < GROUP_FROM_JUNCTION) {
                        failed.add(String.format(Locale.ROOT, "the leader passed %.1f from the junction",
                            lead.closestToJunction));
                    }
                    if (lead.walked > leadClear * (1 + GROUP_OVER)) {
                        failed.add(String.format(Locale.ROOT, "the leader walked %.1f, over 10 %% above the line "
                            + "round the well with the group's clearance %.1f (%.1f)", lead.walked, groupClear, leadClear));
                    }
                }
                // the group phase's own share: the singles before it were the server's first plaza shaping (JIT warm-up
                // on few NPC ticks), reported but not judged
                long phaseTicks = npcs.get().npcTicks() - costBase[1];
                double cost = phaseTicks <= 0 ? Double.NaN
                    : (npcs.get().service().corridors().nanos() - costBase[0]) / 1e6 / phaseTicks;
                if (!(cost <= BUDGET_MS)) {
                    failed.add(String.format(Locale.ROOT, "corridor shaping %.4f ms per NPC per tick in the group phase",
                        cost));
                }
                if (closestWell < GROUP_FROM_WELL) {
                    failed.add(String.format(Locale.ROOT, "a member came %.2f from the well", closestWell));
                }
                report.add("formation keeping", "elastic")
                    .add("leader", result.get() == null ? "still walking after " + GROUP_TICKS / 20 + " s"
                        : result.get().getClass().getSimpleName() + String.format(Locale.ROOT, " after %.1f s", took[0] / 20.0))
                    .add("leader on the plaza", lead == null ? "-" : String.format(Locale.ROOT,
                        "walked %.1f vs line round the well at %.1f %.1f (%+.0f %%), chord %.1f, junction %.1f",
                        lead.walked, groupClear, leadClear, (lead.walked / leadClear - 1) * 100,
                        lead.entered.horizontalDistance(lead.left), lead.closestToJunction))
                    .add("closest member to the well", closestWell == Double.MAX_VALUE ? "-"
                        : String.format(Locale.ROOT, "%.2f blocks", closestWell))
                    .add("width now", group.get().width().describe())
                    .widthChanges(npcs.get(), plaza)
                    .add("furthest behind its slot", String.format(Locale.ROOT, "%.1f blocks", group.get().maxLag()))
                    .add("lost", group.get().lost())
                    .add("shaping in this phase", "%.4f ms per NPC per tick", cost)
                    .plazaLines(npcs.get())
                    .common(npcs.get(), plaza)
                    .log("a group of 7 in ranks of 3");
                return failed.isEmpty() ? null : String.join("; ", failed);
            });
    }

    private static void watch(List<NpcMover> movers, Map<Integer, Crossing> crossings, NpcMovementScene plaza) {
        for (NpcMover mover : movers) {
            NavPoint at = mover.position().orElse(null);
            if (at != null && plaza.onPlaza(at)) {
                crossings.computeIfAbsent(mover.id(), id -> new Crossing()).see(at, plaza);
            }
        }
    }
}
