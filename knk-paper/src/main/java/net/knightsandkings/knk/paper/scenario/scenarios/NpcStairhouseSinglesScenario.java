package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcMovementScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * Plan 4c.5 item 2 (link M1, PandiO/knk-workspace#621), in report mode: 12 singles at once, 6 from the road east of
 * the stair house up through the entrance (1413 49 -508), along the 3-wide straight (1414 49 -503) and up both
 * flights, 6 from the top landing down the same way. Reports arrivals, stuck spells, the longest still single, the
 * longest stand on the straight and in the entrance, falls and the tick cost. Later links check: all arrive, 0 stuck
 * spells, longest still under 3 s, no fall (M2). Runs on the stand-in until the developer's schematic. Needs Citizens.
 */
public final class NpcStairhouseSinglesScenario implements Scenario<ScenarioScene> {

    static final int MAX_TICKS = 50 * 20;

    private final boolean collisionOn;

    public NpcStairhouseSinglesScenario(boolean collisionOn) {
        this.collisionOn = collisionOn;
    }

    @Override
    public String name() {
        return NpcMovementReport.name("npc-stairhouse-singles", collisionOn);
    }

    @Override
    public String description() {
        return "Report: 12 singles both ways through the stair house's entrance, straight and flights (collision "
            + (collisionOn ? "on" : "off") + ")";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcMovementScene house = NpcMovementScene.stairHouse(scene);
        NpcMovementReport report = new NpcMovementReport(scene, name());
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<SingleTrips> trips = new AtomicReference<>();
        AtomicReference<String> built = new AtomicReference<>();
        NpcMovementReport.StillInArea straight = new NpcMovementReport.StillInArea();
        NpcMovementReport.StillInArea entrance = new NpcMovementReport.StillInArea();
        long[] started = {0};
        plan.act("paste the stair house", () -> built.set(house.build()))
            .act("start NPC navigation on Citizens", () -> npcs.set(ScenarioNpcs.start(scene, house.houseLoop(),
                NpcMovementReport.settings(collisionOn))))
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .act("6 singles walk up from the road and 6 down from the top landing, at once", () -> {
                List<NavPoint> starts = new ArrayList<>(house.houseBottomSpots());
                starts.addAll(house.houseTopSpots());
                List<NavPoint> goals = new ArrayList<>(house.houseTopSpots());
                goals.addAll(house.houseBottomSpots());
                report.phaseStarts(npcs.get());
                started[0] = npcs.get().ticks();
                trips.set(SingleTrips.send(npcs.get(), starts, goals));
                npcs.get().everyTick(() -> {
                    for (NpcMover mover : trips.get().movers()) {
                        NavPoint at = mover.position().orElse(null);
                        boolean walking = !trips.get().ended(mover);
                        straight.look(mover, at, walking && at != null && house.onHouseStraight(at), house);
                        entrance.look(mover, at, walking && at != null && house.inHouseEntrance(at), house);
                    }
                });
            })
            .await("every trip ends or the time is up", MAX_TICKS + 20,
                () -> trips.get().allEnded() || npcs.get().ticks() - started[0] >= MAX_TICKS)
            .check("report the numbers (report mode, M1)", () -> {
                report.add("scene", built.get())
                    .add("arrived", trips.get().arrivals())
                    .add("others", trips.get().others())
                    .add("trips", trips.get().tripTimes())
                    .common(npcs.get(), house)
                    .add("longest still on the straight 1414 -503", straight.describe())
                    .add("longest still in the entrance 1413 -508", entrance.describe())
                    .log("12 singles both ways");
                return null;
            });
    }
}
