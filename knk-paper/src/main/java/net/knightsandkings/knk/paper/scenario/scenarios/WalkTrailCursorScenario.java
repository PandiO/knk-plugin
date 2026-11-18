package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import net.knightsandkings.knk.core.navigation.PolylineCursor;
import net.knightsandkings.knk.core.roads.walk.WalkPath;
import net.knightsandkings.knk.core.roads.walk.WalkPathShaper;
import net.knightsandkings.knk.core.roads.walk.WalkRequest;
import net.knightsandkings.knk.core.roads.walk.WalkSearch;
import net.knightsandkings.knk.core.roads.walk.WalkTerrain;
import net.knightsandkings.knk.core.scenario.Scenario;
import net.knightsandkings.knk.core.scenario.ScenarioPlan;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.navigation.TrailRenderer;
import net.knightsandkings.knk.paper.npc.nav.BukkitNavWorld;
import net.knightsandkings.knk.paper.scenario.NpcMovementScene;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.scenario.SizedScene;

/**
 * Off-road navigation phase 6.2 (PandiO/knk-workspace#711, DESIGN § 7.2, A17, A20) on real blocks: the shaped walk trail up
 * the stair house (as {@link WalkTrailStairhouseScenario} builds it, {@code guidance-v2}), walked up and back down by a
 * player position that wobbles a little beside it. The walk leg's cursor ({@link PolylineCursor} with the walk path's
 * window) must follow the player both ways without jumping onto another flight of the stair, and the trail's particles
 * ({@link TrailRenderer#anchoredWindow}) must sit on fixed anchors over walkable floor: a redraw puts them where they
 * were, a step moves none of those ahead.
 */
public final class WalkTrailCursorScenario implements Scenario<ScenarioScene>, SizedScene {

    /** The cursor may lag or lead the player's true place on the trail by this much (the wobble, the samples). */
    static final double CURSOR_SLACK = 1.0;
    /** The player's sideways wobble beside the trail. */
    static final double WOBBLE = 0.25;

    @Override
    public String name() {
        return "walk-trail-cursor-stairhouse";
    }

    @Override
    public String description() {
        return "The walk leg's cursor and the anchored particles on the stair house's shaped trail, walked up and back "
            + "down (guidance-v2)";
    }

    @Override
    public ScenarioScene.Box box() {
        return NpcMovementScene.STAIRHOUSE_BOX;
    }

    @Override
    public void define(ScenarioPlan plan, ScenarioScene scene) {
        NpcMovementScene house = NpcMovementScene.stairHouse(scene);
        NavigationConfig.WalkConfig walk = NavigationConfig.WalkConfig.defaults();
        double spacing = NavigationConfig.TrailConfig.defaults().legSpacing();
        int length = NavigationConfig.TrailConfig.defaults().length();
        BukkitNavWorld live = new BukkitNavWorld(null);
        String w = scene.world().getName();
        AtomicReference<WalkTerrain> terrain = new AtomicReference<>();
        AtomicReference<List<double[]>> trail = new AtomicReference<>();
        plan.act("build the stair house", house::build)
            .act("capture the box as a walk leg does", () -> terrain.set(WalkTrailStairhouseScenario.capture(scene, walk)))
            .check("with guidance-v2 the path is found and shaped", () -> {
                WalkRequest request = WalkTrailStairhouseScenario.request(house, terrain.get(), walk, walk.profile(true));
                WalkPath path = new WalkSearch().find(request).path().orElse(null);
                if (path == null) {
                    return "no way up";
                }
                WalkPathShaper.Shaped shaped = new WalkPathShaper().shape(path, request.terrain(), request.profile(),
                    request.access(), walk.shaping());
                trail.set(shaped.trail());
                return shaped.trail().size() < 2 ? "no shaped trail" : null;
            })
            .check("walking up and back down, the cursor follows the player and keeps to the flight they are on", () -> {
                List<double[]> line = trail.get();
                double[] cumulative = PolylineCursor.cumulative(line);
                List<double[]> up = WalkTrailStairhouseScenario.samples(line);
                double[] alongUp = new double[up.size()];
                for (int i = 0; i < up.size(); i++) {
                    alongUp[i] = PolylineCursor.nearest(line, cumulative, up.get(i)[0], up.get(i)[1], up.get(i)[2],
                        i == 0 ? 0 : alongUp[i - 1] - 0.01, i == 0 ? 0.01 : alongUp[i - 1] + 1.0).along();
                }
                double cursor = Double.NaN;
                double worst = 0;
                String worstAt = "";
                for (int pass = 0; pass < 2; pass++) {
                    for (int k = 0; k < up.size(); k++) {
                        int i = pass == 0 ? k : up.size() - 1 - k;
                        double[] p = up.get(i);
                        double wobble = WOBBLE * Math.sin(i * 0.7);
                        cursor = PolylineCursor.follow(line, cumulative, cursor, p[0] + wobble, p[1], p[2] - wobble,
                            PolylineCursor.PATH_WINDOW, Double.POSITIVE_INFINITY, PolylineCursor.PATH_NEARER).along();
                        double off = Math.abs(cursor - alongUp[i]);
                        if (off > worst) {
                            worst = off;
                            worstAt = (pass == 0 ? "up" : "down") + " at " + WalkTrailStairhouseScenario.describe(p);
                        }
                    }
                }
                String numbers = String.format(Locale.ROOT, "the cursor was %.2f blocks off the player's place, %s "
                    + "(trail %.1f blocks)", worst, worstAt, cumulative[cumulative.length - 1]);
                return worst <= CURSOR_SLACK ? null : numbers;
            })
            .check("the particles sit on fixed anchors over walkable floor; a step moves none of those ahead (A17)", () -> {
                List<double[]> line = trail.get();
                double total = TrailRenderer.polylineLength(line);
                List<double[]> previous = null;
                for (double cursor = 0; cursor <= total; cursor += 0.4) {
                    List<double[]> window = TrailRenderer.anchoredWindow(line, cursor, length, spacing);
                    List<double[]> again = TrailRenderer.anchoredWindow(line, cursor, length, spacing);
                    if (!same(window, again)) {
                        return "two redraws differ at " + cursor;
                    }
                    for (double[] p : window) {
                        if (!WalkTrailStairhouseScenario.overFloor(live, w, p)) {
                            return "a particle off the floor at " + WalkTrailStairhouseScenario.describe(p);
                        }
                    }
                    if (previous != null) {
                        for (double[] p : window) {
                            boolean drawnBefore = previous.stream().anyMatch(q -> Arrays.equals(p, q));
                            if (!drawnBefore && p != window.get(window.size() - 1)) {
                                return "a particle ahead moved at cursor " + cursor + ": "
                                    + WalkTrailStairhouseScenario.describe(p);
                            }
                        }
                    }
                    previous = window;
                }
                return null;
            });
    }

    private static boolean same(List<double[]> a, List<double[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!Arrays.equals(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }
}
