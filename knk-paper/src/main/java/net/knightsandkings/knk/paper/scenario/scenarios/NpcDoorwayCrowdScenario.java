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
 * Plan 4c.5 item 5 (link M1, PandiO/knk-workspace#621): two rooms joined by a one-block doorway, 6 singles in each room
 * walk to the other room at once. Reports arrivals, the longest still single (outside a queue), the ticks two or more
 * NPCs stood in the doorway's cell at once (overlap) and the most at once, crowd awareness's waits and yields, stuck
 * spells and the tick cost.
 * <p>
 * Link M4 (PandiO/knk-workspace#626) runs it with {@code crowd-awareness} on (and {@code local-legs-clearance}, since
 * every trip here is a short local leg through the doorway) and checks plan 4c.4 item 4.3: all arrive, nobody waits
 * longer than the queue ahead of it needs, nobody still longer than 3 s outside a queue, 0 stuck spells. Needs
 * Citizens.
 */
public final class NpcDoorwayCrowdScenario implements Scenario<ScenarioScene> {

    static final int MAX_TICKS = 30 * 20;

    private final boolean collisionOn;

    public NpcDoorwayCrowdScenario(boolean collisionOn) {
        this.collisionOn = collisionOn;
    }

    @Override
    public String name() {
        return NpcMovementReport.name("npc-doorway-crowd", collisionOn);
    }

    @Override
    public String description() {
        return "6 singles from each side through a one-block doorway at once, crowd awareness on (collision "
            + (collisionOn ? "on" : "off") + ")";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcMovementScene rooms = NpcMovementScene.doorway(scene);
        NpcMovementReport report = new NpcMovementReport(scene, name());
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<SingleTrips> trips = new AtomicReference<>();
        AtomicReference<String> built = new AtomicReference<>();
        NpcMovementReport.StillInArea nearDoor = new NpcMovementReport.StillInArea();
        long[] started = {0};
        int[] overlapTicks = {0};
        int[] mostInDoor = {0};
        plan.act("paste the two rooms and the doorway", () -> built.set(rooms.build()))
            .act("start NPC navigation on Citizens", () -> npcs.set(ScenarioNpcs.start(scene, rooms.doorLoop(),
                NpcMovementReport.settings(collisionOn, true, true, false, false, true))))
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .act("6 singles in each room walk to the other room, at once", () -> {
                List<NavPoint> starts = new ArrayList<>(rooms.doorRoom(true));
                starts.addAll(rooms.doorRoom(false));
                List<NavPoint> goals = new ArrayList<>(rooms.doorRoom(false));
                goals.addAll(rooms.doorRoom(true));
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
                        boolean near = at != null && Math.abs(at.x() - rooms.feet(12.5, 1, 7.5).x()) <= 3
                            && Math.abs(at.z() - rooms.feet(12.5, 1, 7.5).z()) <= 3;
                        nearDoor.look(mover, at, !trips.get().ended(mover) && near, rooms);
                    }
                    if (inDoor >= 2) {
                        overlapTicks[0]++;
                    }
                    mostInDoor[0] = Math.max(mostInDoor[0], inDoor);
                });
            })
            .await("every trip ends or the time is up", MAX_TICKS + 20,
                () -> trips.get().allEnded() || npcs.get().ticks() - started[0] >= MAX_TICKS)
            .check("all arrive, no wait longer than the queue needs, nobody still over 3 s, 0 stuck spells (M4)", () -> {
                java.util.List<String> failed = new ArrayList<>();
                if (!trips.get().allEnded() || trips.get().arrived() != trips.get().movers().size()) {
                    failed.add("arrived " + trips.get().arrivals() + " (" + trips.get().others() + ")");
                }
                String crowd = report.crowdFailures(npcs.get());
                if (crowd != null) {
                    failed.add(crowd);
                }
                if (npcs.get().longestStillTicks() > NpcMovementReport.MAX_STILL_TICKS) {
                    failed.add(String.format(java.util.Locale.ROOT, "still for %.1f s outside a queue (%s)",
                        npcs.get().longestStillTicks() / 20.0, npcs.get().longestStillWho()));
                }
                report.add("scene", built.get())
                    .add("arrived", trips.get().arrivals())
                    .add("others", trips.get().others())
                    .add("trips", trips.get().tripTimes())
                    .add("overlap in the doorway", overlapTicks[0] + " ticks (most at once " + mostInDoor[0] + ")")
                    .add("longest still within 3 blocks of the doorway", nearDoor.describe())
                    .crowd(npcs.get())
                    .common(npcs.get(), rooms)
                    .log("6 + 6 singles through the doorway");
                return failed.isEmpty() ? null : String.join("; ", failed);
            });
    }
}
