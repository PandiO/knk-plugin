package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcMovementScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * Plan 4c.5 item 2 on the developer's schematic (link M1, PandiO/knk-workspace#621; checks since link M2, #622): 12
 * singles at once, 6 from the street north of the stair house through the entrance (1413 49 -508), along the straight
 * (1414 49 -503) and the 3-wide passage and up the two steps into the hall, 6 from the hall down the same way, with
 * {@code corridor-steering} and {@code local-legs-clearance} on. Checks (plan 4c.4 items 2.3 and 6.3): all arrive, 0
 * stuck spells, nobody still longer than 3 s, no fall, corridor steering's cost within its share of the budget, no local
 * leg without a path (no {@code none}, no {@code recovery_no_path}). Still reports the numbers (arrivals, trips, the
 * longest stand on the straight and in the entrance, the tick cost) in its {@code KNK-NPC-REPORT} line. Needs Citizens.
 */
public final class NpcStairhouseSinglesScenario implements Scenario<ScenarioScene>, net.knightsandkings.knk.paper.scenario.SizedScene {

    /** The whole stair house (link B1, PandiO/knk-workspace#670). */
    @Override
    public ScenarioScene.Box box() {
        return NpcMovementScene.STAIRHOUSE_BOX;
    }

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
        return "12 singles both ways through the stair house's entrance, straight and steps with corridor steering: "
            + "all arrive, none stuck or still over 3 s (collision " + (collisionOn ? "on" : "off") + ")";
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
                NpcMovementReport.settings(collisionOn, true, true))))
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .act("6 singles walk in from the street and 6 out from the hall, at once", () -> {
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
            .check("all arrive, 0 stuck spells, nobody still over 3 s, no fall (M2); no local leg without a path (M6)", () -> {
                report.add("scene", built.get())
                    .add("arrived", trips.get().arrivals())
                    .add("others", trips.get().others())
                    .add("trips", trips.get().tripTimes())
                    .common(npcs.get(), house)
                    .add("longest still on the straight 1414 -503", straight.describe())
                    .add("longest still in the entrance 1413 -508", entrance.describe())
                    .corridor(npcs.get());
                String failed = report.singlesFailures(npcs.get(), trips.get());
                String legs = report.localLegFailures(npcs.get());
                failed = legs == null ? failed : failed == null ? legs : failed + "; " + legs;
                report.log("12 singles both ways");
                return failed;
            });
    }
}
