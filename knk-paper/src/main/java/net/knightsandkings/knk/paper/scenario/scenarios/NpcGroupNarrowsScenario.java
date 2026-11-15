package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Material;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.scenario.NpcCornerScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * Narrowed formations on a real server (implementation plan 4b.3 item 14.1, PandiO/knk-workspace#493): a group of 9
 * in {@code cluster} walks the corner scene from the north road (3 wide, with a one-block post on its outer lane)
 * down the east side through its 2-wide and 1-wide passages to the south road (3 wide). It walks 3 abreast, narrows
 * to 2 abreast, folds into a column and takes up 3 abreast again, in that order; the post changes nothing; nobody
 * trespasses or falls and no stuck spell is left open. Needs Citizens (CI's {@code paper-boot} server).
 */
public final class NpcGroupNarrowsScenario implements Scenario<ScenarioScene> {

    static final int MAX_TICKS = 90 * 20;

    @Override
    public String name() {
        return "npc-group-narrows";
    }

    @Override
    public String description() {
        return "A cluster of 9 on 3-, 2- and 1-wide passages: 3 abreast, 2 abreast, column and back, no switch at a post";
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcCornerScene corners = NpcCornerScene.in(scene);
        AtomicReference<ScenarioNpcs> npcs = new AtomicReference<>();
        AtomicReference<NavGroup> group = new AtomicReference<>();
        List<String> widths = new ArrayList<>();
        AtomicReference<String> widthAtGoal = new AtomicReference<>();
        NavPoint goal = corners.feet(18.5, NpcCornerScene.GROUND, 26.5); // the south road, 3 wide, before the flight
        NavPoint post = corners.feet(16.5, NpcCornerScene.GROUND, 6.5);
        plan.act("build the corner scene with a post on the north road's outer lane", () -> {
                corners.build();
                scene.block(16, 2, 6).setType(Material.STONE_BRICKS);
                scene.block(16, 3, 6).setType(Material.STONE_BRICKS);
            })
            .act("start NPC navigation on Citizens", () -> npcs.set(ScenarioNpcs.start(scene, corners.routeLine(),
                NavigationSettings.defaults())))
            .cleanup("remove the NPCs", () -> {
                if (npcs.get() != null) {
                    npcs.get().stop();
                }
            })
            .act("a group of 9 in cluster walks from the north road to the south road", () -> {
                List<NpcMover> members = new ArrayList<>();
                for (int i = 0; i < 9; i++) {
                    members.add(npcs.get().spawn(corners.feet(10.5 - (i / 3) * 1.5, NpcCornerScene.GROUND, 4.5 + (i % 3))));
                }
                group.set(npcs.get().patrolGroup(members, Formation.Type.CLUSTER, goal, corners.start()));
            })
            .await("the leader reaches the south road", MAX_TICKS, () -> {
                NavPoint at = group.get().leader().position().orElse(null);
                if (at != null && at.horizontalDistance(goal) <= 1.5 && widthAtGoal.get() == null) {
                    widthAtGoal.set(group.get().width().describe());
                    for (var line : npcs.get().log()) {
                        String text = line.text();
                        if (text.startsWith("group ") && (text.contains(" narrows ") || text.contains(" widens ")
                            || text.contains(" folds ") || text.contains(" takes up "))) {
                            widths.add(text);
                        }
                    }
                }
                return widthAtGoal.get() != null;
            })
            .check("3 abreast, 2 abreast, a column and 3 abreast again, in that order", () -> {
                int narrow = indexOf(widths, "narrows to 2 abreast");
                int fold = indexOf(widths, "folds into a column");
                int back = Math.max(indexOf(widths, "takes up 3 abreast again"), indexOf(widths, "widens to 3 abreast"));
                return narrow >= 0 && fold > narrow && back > fold ? null
                    : "width changes " + widths + npcs.get().tail(20);
            })
            .check("the post on the north road changed nothing", () -> {
                for (String line : widths) {
                    int[] at = position(line);
                    if (at != null && Math.abs(at[0] - post.blockX()) <= 3 && Math.abs(at[2] - post.blockZ()) <= 3) {
                        return "a width change at the post: " + line;
                    }
                }
                return null;
            })
            .check("the group came out of the passages 3 abreast", () -> widthAtGoal.get().equals("3 abreast") ? null
                : "at the south road: " + widthAtGoal.get() + ", changes " + widths)
            .waitTicks(60)
            .check("nobody trespassed or fell", () -> npcs.get().metrics().total(Event.TRESPASS) == 0
                && npcs.get().metrics().total(Event.FELL) == 0 ? null : npcs.get().stats() + npcs.get().tail(20))
            .check("no stuck spell is left open", () -> npcs.get().metrics().spells().open() == 0 ? null
                : npcs.get().stats() + npcs.get().tail(20))
            .check("the counters add up", () -> npcs.get().countersAddUp());
    }

    private static int indexOf(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        return -1;
    }

    /** The block position at the end of a width line ("... at x y z"), or null. */
    static int[] position(String line) {
        String[] words = line.trim().split(" ");
        if (words.length < 4 || !words[words.length - 4].equals("at")) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(words[words.length - 3]), Integer.parseInt(words[words.length - 2]),
                Integer.parseInt(words[words.length - 1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
