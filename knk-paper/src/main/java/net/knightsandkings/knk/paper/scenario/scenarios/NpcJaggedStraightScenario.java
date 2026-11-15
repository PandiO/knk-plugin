package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcMovementScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * Plan 4c.5 item 4 (link M1, PandiO/knk-workspace#621), in report mode: a 3-wide straight with one-block notches in
 * both walls every 2 to 3 blocks, a lantern on a fence post and a stair block beside the road, walked on the road line
 * the skeleton gives (the middle of the local width, wobbling at every notch). First 12 singles at once, 6 from each
 * end; then a group of 9 in {@code ranks} from the north end to the south end. Reports arrivals, stuck spells, the
 * longest still single, the group's width changes with their reasons and the tick cost. Later links check: 0 stuck
 * spells (M2), the group keeps 3 or 2 abreast with 0 folds (M3). Needs Citizens.
 */
public final class NpcJaggedStraightScenario implements Scenario<ScenarioScene> {

    static final int SINGLES_TICKS = 30 * 20;
    static final int GROUP_TICKS = 30 * 20;

    private final boolean collisionOn;

    public NpcJaggedStraightScenario(boolean collisionOn) {
        this.collisionOn = collisionOn;
    }

    @Override
    public String name() {
        return NpcMovementReport.name("npc-jagged-straight", collisionOn);
    }

    @Override
    public String description() {
        return "Report: 12 singles both ways, then a group of 9 in ranks, on a jagged 3-wide straight (collision "
            + (collisionOn ? "on" : "off") + ")";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcMovementScene road = NpcMovementScene.jaggedStraight(scene);
        NpcMovementReport report = new NpcMovementReport(scene, name());
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<SingleTrips> trips = new AtomicReference<>();
        AtomicReference<NavGroup> group = new AtomicReference<>();
        AtomicReference<NavigationResult> result = new AtomicReference<>();
        AtomicReference<String> built = new AtomicReference<>();
        long[] started = {0};
        long[] took = {-1};
        plan.act("paste the jagged straight", () -> built.set(road.build()))
            .act("start NPC navigation on Citizens", () -> npcs.set(ScenarioNpcs.start(scene, road.jaggedLoop(),
                NpcMovementReport.settings(collisionOn))))
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .act("6 singles from each end walk to the other end, at once", () -> {
                List<NavPoint> starts = new ArrayList<>(road.jaggedEnd(true));
                starts.addAll(road.jaggedEnd(false));
                List<NavPoint> goals = new ArrayList<>(road.jaggedEnd(false));
                goals.addAll(road.jaggedEnd(true));
                report.phaseStarts(npcs.get());
                started[0] = npcs.get().ticks();
                trips.set(SingleTrips.send(npcs.get(), starts, goals));
            })
            .await("every trip ends or the time is up", SINGLES_TICKS + 20,
                () -> trips.get().allEnded() || npcs.get().ticks() - started[0] >= SINGLES_TICKS)
            .check("report the singles (report mode, M1)", () -> {
                report.add("scene", built.get())
                    .add("arrived", trips.get().arrivals())
                    .add("others", trips.get().others())
                    .add("trips", trips.get().tripTimes())
                    .common(npcs.get(), road)
                    .log("12 singles both ways");
                return null;
            })
            .act("the singles leave; a group of 9 in ranks walks from the north end to the south end", () -> {
                npcs.get().remove(trips.get().movers());
                List<NpcMover> members = new ArrayList<>();
                for (int i = 0; i < 9; i++) {
                    members.add(npcs.get().spawn(i == 0 ? road.feet(15.5, NpcMovementScene.FLAT, 6.5)
                        : road.feet(14.5 + ((i - 1) % 3), NpcMovementScene.FLAT, 5.0 - 1.5 * ((i - 1) / 3))));
                }
                report.phaseStarts(npcs.get());
                started[0] = npcs.get().ticks();
                group.set(npcs.get().group(members, Formation.Type.RANKS));
                group.get().navigate(road.feet(15.5, NpcMovementScene.FLAT, 29.5), r -> {
                    result.set(r);
                    took[0] = npcs.get().ticks() - started[0];
                });
            })
            .await("the group's trip ends or the time is up", GROUP_TICKS + 20,
                () -> result.get() != null || npcs.get().ticks() - started[0] >= GROUP_TICKS)
            .check("report the group (report mode, M1)", () -> {
                report.add("leader", result.get() == null ? "still walking after " + GROUP_TICKS / 20 + " s"
                        : result.get().getClass().getSimpleName() + String.format(Locale.ROOT, " after %.1f s", took[0] / 20.0))
                    .add("width now", group.get().width().describe())
                    .widthChanges(npcs.get(), road)
                    .add("furthest behind its slot", String.format(Locale.ROOT, "%.1f blocks", group.get().maxLag()))
                    .add("lost", group.get().lost())
                    .common(npcs.get(), road)
                    .log("a group of 9 in ranks");
                return null;
            });
    }
}
