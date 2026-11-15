package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcMovementScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * Plan 4c.5 item 6 (link M1, PandiO/knk-workspace#621), in report mode: a 15 × 15 plaza with four roads and a well
 * off-centre; 3 singles cross from the west road to the north road on the road graph's line (to the plaza junction in
 * the middle and out again). Reports each NPC's walked length on the plaza against the chord from where it entered to
 * where it left, how close it came to the well, arrivals and the tick cost. Later links check: the walked length
 * within 5 % of the chord's, the well passed with the wall clearance (M5). Needs Citizens.
 */
public final class NpcPlazaLineScenario implements Scenario<ScenarioScene> {

    static final int MAX_TICKS = 25 * 20;

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
        return "Report: 3 singles cross a plaza from the west road to the north road, walked length vs chord (collision "
            + (collisionOn ? "on" : "off") + ")";
    }

    /** One NPC's way across the plaza. */
    private static final class Crossing {
        NavPoint entered;
        NavPoint left;
        NavPoint last;
        double walked;
        double closestToWell = Double.MAX_VALUE;
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcMovementScene plaza = NpcMovementScene.plaza(scene);
        NpcMovementReport report = new NpcMovementReport(scene, name());
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<SingleTrips> trips = new AtomicReference<>();
        AtomicReference<String> built = new AtomicReference<>();
        Map<Integer, Crossing> crossings = new HashMap<>();
        long[] started = {0};
        plan.act("paste the plaza", () -> built.set(plaza.build()))
            .act("start NPC navigation on Citizens", () -> npcs.set(ScenarioNpcs.start(scene, plaza.plazaLoop(),
                NpcMovementReport.settings(collisionOn))))
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
                    for (NpcMover mover : trips.get().movers()) {
                        NavPoint at = mover.position().orElse(null);
                        if (at == null || !plaza.onPlaza(at)) {
                            continue;
                        }
                        Crossing c = crossings.computeIfAbsent(mover.id(), id -> new Crossing());
                        if (c.entered == null) {
                            c.entered = at;
                        } else {
                            c.walked += c.last.horizontalDistance(at);
                        }
                        c.last = at;
                        c.left = at;
                        c.closestToWell = Math.min(c.closestToWell, plaza.fromWell(at));
                    }
                });
            })
            .await("every trip ends or the time is up", MAX_TICKS + 20,
                () -> trips.get().allEnded() || npcs.get().ticks() - started[0] >= MAX_TICKS)
            .check("report the numbers (report mode, M1)", () -> {
                StringBuilder lines = new StringBuilder();
                double worst = 0;
                double closest = Double.MAX_VALUE;
                for (NpcMover mover : trips.get().movers()) {
                    Crossing c = crossings.get(mover.id());
                    if (c == null || c.entered == null) {
                        lines.append(lines.isEmpty() ? "" : "; ").append(mover.name()).append(" never on the plaza");
                        continue;
                    }
                    double chord = c.entered.horizontalDistance(c.left);
                    double over = chord < 1e-6 ? 0 : (c.walked / chord - 1) * 100;
                    worst = Math.max(worst, over);
                    closest = Math.min(closest, c.closestToWell);
                    lines.append(lines.isEmpty() ? "" : "; ").append(String.format(Locale.ROOT,
                        "%s walked %.1f vs chord %.1f (+%.0f %%), well %.2f", mover.name(), c.walked, chord, over,
                        c.closestToWell));
                }
                report.add("scene", built.get())
                    .add("arrived", trips.get().arrivals())
                    .add("others", trips.get().others())
                    .add("on the plaza", lines.isEmpty() ? "-" : lines.toString())
                    .add("most over the chord", "%.0f %%", worst)
                    .add("closest to the well", closest == Double.MAX_VALUE ? "-" : String.format(Locale.ROOT, "%.2f blocks", closest))
                    .common(npcs.get(), plaza)
                    .log("3 singles west road to north road");
                return null;
            });
    }
}
