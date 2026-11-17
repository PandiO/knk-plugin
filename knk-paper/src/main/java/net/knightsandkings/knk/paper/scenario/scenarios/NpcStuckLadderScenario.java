package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Material;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * The recovery ladder on a real NPC (implementation plan 4b.2 item 18.3, PandiO/knk-workspace#521): an NPC walking a
 * road east is pushed into a pocket beside it, walled in on three sides (north, east, south; open to the west). Its
 * next waypoint lies to the north-east beyond the walls, so it walks into the pocket's inside corner and is pinned there
 * (a flat wall would let it slide out). It frees itself within the cap, taking the steps in order (its footsteps from
 * before the push are no way back, a side-step of one block does not reach a straight line out, the local leg does),
 * the run log's count never passes the cap, and no two tries of a spell come within {@code recovery-wait-ticks} of
 * each other. With {@code local-legs-clearance} on (link M6, PandiO/knk-workspace#623): the ladder's local leg frees it,
 * found by K&K's own search on its walk grid, and no leg needs the engine's search. Needs Citizens.
 */
public final class NpcStuckLadderScenario implements Scenario<ScenarioScene> {

    private static final Pattern STEP = Pattern.compile("^(\\S+) stuck \\(.*: step (\\d+) of (\\d+) ");

    @Override
    public String name() {
        return "npc-stuck-ladder";
    }

    @Override
    public String description() {
        return "An NPC pushed into a walled corner frees itself within the cap, steps in order, tries spaced";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<NpcMover> walker = new AtomicReference<>();
        String w = scene.world().getName();
        // a road east along z 4 and 5 (floor y 1) and a pocket at x 14 z 7 south of it, open to the west only
        List<NavPoint> road = List.of(new NavPoint(w, scene.worldX(2) + 0.5, scene.worldY(1), scene.worldZ(5) + 0.5),
            new NavPoint(w, scene.worldX(29) + 0.5, scene.worldY(1), scene.worldZ(5) + 0.5),
            new NavPoint(w, scene.worldX(29) + 0.5, scene.worldY(1), scene.worldZ(4) + 0.5),
            new NavPoint(w, scene.worldX(2) + 0.5, scene.worldY(1), scene.worldZ(4) + 0.5),
            new NavPoint(w, scene.worldX(2) + 0.5, scene.worldY(1), scene.worldZ(5) + 0.5));
        plan.act("build a road with a pocket walled in on three sides beside it", () -> {
                ScenarioGates.floor(scene, 0, 0, 31, 31, 1, Material.STONE);
                int[][] walls = {{14, 6}, {15, 6}, {15, 7}, {15, 8}, {14, 8}};
                for (int[] wall : walls) {
                    for (int y = 2; y <= 4; y++) {
                        scene.block(wall[0], y, wall[1]).setType(Material.STONE_BRICKS);
                    }
                }
            })
            .act("start NPC navigation on Citizens", () -> npcs.set(ScenarioNpcs.start(scene, road,
                NavigationSettings.builder().global(NavigationSettings.Overrides.of(
                    NavigationSettings.LOCAL_LEGS_CLEARANCE, true)).build())))
            .cleanup("remove the NPC", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .act("an NPC walks the road east", () -> {
                walker.set(npcs.get().spawn(new NavPoint(w, scene.worldX(4) + 0.5, scene.worldY(2), scene.worldZ(5) + 0.5)));
                npcs.get().patrol(walker.get(), new NavPoint(w, scene.worldX(28) + 0.5, scene.worldY(2),
                    scene.worldZ(5) + 0.5), new NavPoint(w, scene.worldX(3) + 0.5, scene.worldY(2), scene.worldZ(4) + 0.5));
            })
            .await("it walked past x 17", 20 * 10, () -> walker.get().position().map(p -> p.x() > scene.worldX(17))
                .orElse(false))
            .act("push it into the pocket", () -> npcs.get().trace(walker.get()))
            .act("(pushed)", () -> npcs.get().push(walker.get(),
                new NavPoint(w, scene.worldX(14) + 0.5, scene.worldY(2), scene.worldZ(7) + 0.5)))
            .waitTicks(2)
            .check("it is in the pocket", () -> walker.get().position().map(p -> p.blockX() == scene.worldX(14)
                && p.blockZ() == scene.worldZ(7)).orElse(false) ? null
                : "the push did not take: " + walker.get().position().map(Object::toString).orElse("not spawned"))
            .waitTicks(20 * 12)
            .check("it got stuck and free again", () -> npcs.get().metrics().total(Event.RECOVERED) >= 1 ? null
                : npcs.get().stats() + npcs.get().tail(60))
            .check("freed by a step of the ladder within the cap, never left stuck or replanned", () -> {
                var m = npcs.get().metrics();
                long byStep = m.total(Event.FREED_BACK_OFF) + m.total(Event.FREED_SIDE_STEP) + m.total(Event.FREED_LOCAL_LEG);
                return byStep >= 1 && m.total(Event.NOT_FREED) == 0 && m.spells().maxSteps() <= 3 ? null
                    : npcs.get().stats() + npcs.get().tail(20);
            })
            .check("no back-off towards footsteps from before the push", () -> npcs.get().log().stream()
                .anyMatch(line -> line.text().contains(" back-off to ")) ? "a back-off through the wall: "
                + npcs.get().tail(20) : null)
            .check("the log's count never passes the cap and no two tries come within recovery-wait-ticks", () -> {
                int wait = NavigationSettings.RECOVERY_WAIT_TICKS.defaultValue();
                Map<String, Long> lastTry = new HashMap<>();
                for (ScenarioNpcs.LogLine line : npcs.get().log()) {
                    Matcher m = STEP.matcher(line.text());
                    if (line.text().contains(" freed at ")) {
                        lastTry.remove(line.text().split(" ")[0]);
                    }
                    if (!m.find()) {
                        continue;
                    }
                    if (Integer.parseInt(m.group(2)) > Integer.parseInt(m.group(3))) {
                        return "past the cap: " + line.text();
                    }
                    Long last = lastTry.put(m.group(1), line.tick());
                    if (last != null && line.tick() - last < wait) {
                        return "two tries " + (line.tick() - last) + " ticks apart: " + npcs.get().tail(20);
                    }
                }
                return null;
            })
            .check("the ladder's local leg freed it, found by K&K without an engine search (M6)", () -> {
                var m = npcs.get().metrics();
                new NpcMovementReport(scene, name())
                    .add("freed", "back-off %d, side-step %d, local leg %d, replan %d, not freed %d",
                        m.total(Event.FREED_BACK_OFF), m.total(Event.FREED_SIDE_STEP), m.total(Event.FREED_LOCAL_LEG),
                        m.total(Event.FREED_REPLAN), m.total(Event.NOT_FREED))
                    .add("steps per spell max", m.spells().maxSteps())
                    .add("recovery no path", m.total(Event.RECOVERY_NO_PATH))
                    .add("legs", npcs.get().localLegs())
                    .log("pushed into the pocket");
                return m.total(Event.FREED_LOCAL_LEG) >= 1 && m.total(Event.LOCAL_LEG_KNK) >= 1
                    && m.total(Event.LOCAL_LEG_ENGINE) == 0 && m.total(Event.LOCAL_LEG_NONE) == 0
                    && m.total(Event.RECOVERY_NO_PATH) == 0 ? null
                    : npcs.get().localLegs() + " | " + npcs.get().stats() + npcs.get().tail(20);
            })
            .check("the counters add up", () -> npcs.get().countersAddUp());
    }
}
