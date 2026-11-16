package net.knightsandkings.knk.core.npc.nav.corridor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.PlazaCrossing;

/**
 * Plaza lines (implementation plan 4c.4 item 5.2, link M5): on the scenario's plaza (15 × 15, x and z 8..22, walled,
 * four 3-wide roads meeting it in the middle of each side) a route from the west road to the north road through the
 * plaza junction (15.5, 15.5) becomes the chord: within 5 % of its length and never near the junction; a well on the
 * chord is passed with the wall clearance; a region the line does not pass is kept out of; a junction of narrow streets
 * is no plaza; plaza lines alone leave the rest of the stretch as it was; the cost is bounded.
 */
class PlazaLineTest {

    private static final CorridorShaper.Options M2 = new CorridorShaper.Options(4, 0.8);
    private static final CorridorShaper.Options PLAZAS = M2.withPlazas(16, 0.8);

    private final GridWorld world = new GridWorld();
    private final AtomicLong tick = new AtomicLong();
    private final CorridorShaper shaper = new CorridorShaper(tick::get, null);

    /** The plaza and its four roads, as tools/scenario-scenes/make_standins.py builds it. */
    private void plaza() {
        world.walls(7, 7, 23, 7).walls(7, 23, 23, 23).walls(7, 7, 7, 23).walls(23, 7, 23, 23);
        for (int a = 14; a <= 16; a++) {
            world.walls.remove(GridWorld.key(a, 7));
            world.walls.remove(GridWorld.key(a, 23));
            world.walls.remove(GridWorld.key(7, a));
            world.walls.remove(GridWorld.key(23, a));
        }
        world.walls(13, -6, 13, 6).walls(17, -6, 17, 6).walls(-6, 13, 6, 13).walls(-6, 17, 6, 17);
        world.walls(13, 24, 13, 36).walls(17, 24, 17, 36).walls(24, 13, 36, 13).walls(24, 17, 36, 17);
    }

    private void well() {
        world.walls(11, 11, 12, 12);
    }

    /** The road graph's V: the west road to the plaza junction, then up the north road; feet every 1.5 blocks. */
    private List<NavPoint> vLine() {
        List<NavPoint> out = new ArrayList<>(line(-4.5, 15.5, 15.5, 15.5));
        List<NavPoint> up = line(15.5, 15.5, 15.5, -4.5);
        out.addAll(up.subList(1, up.size()));
        return out;
    }

    private List<NavPoint> line(double ax, double az, double bx, double bz) {
        List<NavPoint> out = new ArrayList<>();
        double len = Math.hypot(bx - ax, bz - az);
        int n = (int) Math.ceil(len / 1.5);
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            out.add(world.feet(ax + (bx - ax) * t, az + (bz - az) * t));
        }
        return out;
    }

    private static List<Integer> indexes(int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return out;
    }

    private static PlazaCrossing junction(int radius) {
        return new PlazaCrossing(new NavPoint(GridWorld.W, 15.5, 64, 15.5), radius);
    }

    private CorridorShaper.ShapedLine shape(List<NavPoint> feet, CorridorShaper.Options options, int radius) {
        return shaper.shape(world, feet, indexes(feet.size()), options, true, List.of(junction(radius)));
    }

    /** The line's samples every 0.05 block. */
    private static List<double[]> samples(List<NavPoint> p) {
        List<double[]> out = new ArrayList<>();
        for (int k = 1; k < p.size(); k++) {
            NavPoint a = p.get(k - 1);
            NavPoint b = p.get(k);
            int n = Math.max(1, (int) Math.ceil(a.horizontalDistance(b) / 0.05));
            for (int i = 0; i <= n; i++) {
                double t = (double) i / n;
                out.add(new double[] {a.x() + (b.x() - a.x()) * t, a.z() + (b.z() - a.z()) * t});
            }
        }
        return out;
    }

    private static boolean onPlaza(double[] s) {
        return s[0] >= 8 && s[0] < 23 && s[1] >= 8 && s[1] < 23;
    }

    /** {walked on the plaza, the chord from where the line entered it to where it left}. */
    private static double[] walkedAndChord(List<NavPoint> p) {
        List<double[]> s = samples(p);
        double[] first = null;
        double[] last = null;
        double walked = 0;
        double[] prev = null;
        for (double[] q : s) {
            if (onPlaza(q)) {
                if (first == null) {
                    first = q;
                } else {
                    walked += Math.hypot(q[0] - prev[0], q[1] - prev[1]);
                }
                prev = q;
                last = q;
            }
        }
        assertTrue(first != null, "the line crosses the plaza: " + p);
        return new double[] {walked, Math.hypot(last[0] - first[0], last[1] - first[1])};
    }

    private static double closestTo(List<NavPoint> p, double x, double z) {
        double best = Double.MAX_VALUE;
        for (double[] q : samples(p)) {
            best = Math.min(best, Math.hypot(q[0] - x, q[1] - z));
        }
        return best;
    }

    /** The distance from the line to the well's 2 × 2 square (x and z 11..13). */
    private static double fromWell(List<NavPoint> p) {
        double best = Double.MAX_VALUE;
        for (double[] q : samples(p)) {
            double dx = Math.max(Math.max(11 - q[0], 0), q[0] - 13);
            double dz = Math.max(Math.max(11 - q[1], 0), q[1] - 13);
            best = Math.min(best, Math.hypot(dx, dz));
        }
        return best;
    }

    @Test
    void anOpenPlazaIsCrossedOnTheChord() {
        plaza();
        CorridorShaper.ShapedLine before = shaper.shape(world, vLine(), indexes(vLine().size()), M2, true);
        double[] m2 = walkedAndChord(before.waypoints());
        assertTrue(m2[0] > m2[1] * 1.10, "M2's corridor alone can't reach the chord: " + m2[0] + " vs " + m2[1]);

        CorridorShaper.ShapedLine shaped = shape(vLine(), PLAZAS, 0);

        assertEquals(1, shaped.plazaLines());
        double[] wc = walkedAndChord(shaped.waypoints());
        assertTrue(wc[0] <= wc[1] * 1.05, "within 5 % of the chord: " + wc[0] + " vs " + wc[1] + " "
            + shaped.waypoints());
        assertTrue(closestTo(shaped.waypoints(), 15.5, 15.5) > 3, "never through the junction's centre");
        for (NavPoint q : shaped.waypoints()) {
            assertTrue(shaped.corridorAt(q) != null, q + " on its corridor's floor");
        }
    }

    @Test
    void theJunctionsCentreIsPassedWhenItIsOnTheChord() {
        plaza();
        // straight across: west road to east road, the junction on the chord
        List<NavPoint> feet = line(-4.5, 15.5, 35.5, 15.5);
        CorridorShaper.ShapedLine shaped = shape(feet, PLAZAS, 0);
        assertTrue(closestTo(shaped.waypoints(), 15.5, 15.5) < 0.5, "straight through: " + shaped.waypoints());
        double[] wc = walkedAndChord(shaped.waypoints());
        assertTrue(wc[0] <= wc[1] * 1.05);
    }

    @Test
    void aWellOnTheChordIsPassedWithTheWallClearance() {
        plaza();
        well();
        CorridorShaper.ShapedLine shaped = shape(vLine(), PLAZAS, 0);

        assertEquals(1, shaped.plazaLines());
        assertTrue(fromWell(shaped.waypoints()) >= 0.8 - 0.06, "the well passed with the clearance: "
            + fromWell(shaped.waypoints()) + " " + shaped.waypoints());
        double[] wc = walkedAndChord(shaped.waypoints());
        // the shortest way round the well with 0.8 is about 6 % over the straight chord
        assertTrue(wc[0] <= wc[1] * 1.12, "round the well, not through the middle: " + wc[0] + " vs " + wc[1]
            + " " + shaped.waypoints());
        assertTrue(closestTo(shaped.waypoints(), 15.5, 15.5) > 2, "not through the junction's centre");
    }

    @Test
    void aGroupsPlazaLineKeepsItsFormationsHalfWidthFromTheWell() {
        plaza();
        well();
        CorridorShaper.ShapedLine shaped = shape(vLine(), M2.withPlazas(16, 1.8), 0);
        assertTrue(fromWell(shaped.waypoints()) >= 1.8 - 0.06, "3 abreast pass the well: "
            + fromWell(shaped.waypoints()));
    }

    @Test
    void aRegionTheLineDoesNotPassIsKeptOutOf() {
        plaza();
        for (int x = -6; x <= 36; x++) {
            for (int z = -6; z <= 36; z++) {
                world.regions.put(GridWorld.key(x, z), Set.of("town"));
            }
        }
        for (int x = 9; x <= 13; x++) {
            for (int z = 9; z <= 13; z++) {
                world.regions.put(GridWorld.key(x, z), Set.of("town", "private-garden"));
            }
        }
        CorridorShaper.ShapedLine shaped = shape(vLine(), PLAZAS, 0);

        assertEquals(1, shaped.plazaLines());
        for (double[] q : samples(shaped.waypoints())) {
            int x = (int) Math.floor(q[0]);
            int z = (int) Math.floor(q[1]);
            assertFalse(x >= 9 && x <= 13 && z >= 9 && z <= 13, "never in the garden: " + q[0] + " " + q[1]);
        }
    }

    @Test
    void aDesignedPlazasRadiusIsUsed() {
        plaza();
        CorridorShaper.ShapedLine shaped = shape(vLine(), PLAZAS, 7);
        assertEquals(1, shaped.plazaLines());
        // straight from where the line enters the designed footprint (7 blocks from the junction) to where it leaves
        List<NavPoint> p = shaped.waypoints();
        boolean chord = false;
        for (int k = 1; k < p.size(); k++) {
            double ra = Math.hypot(p.get(k - 1).x() - 15.5, p.get(k - 1).z() - 15.5);
            double rb = Math.hypot(p.get(k).x() - 15.5, p.get(k).z() - 15.5);
            chord |= ra > 5.5 && ra <= 7.01 && rb > 5.5 && rb <= 7.01 && p.get(k - 1).x() < 10 && p.get(k).z() < 10;
        }
        assertTrue(chord, "one straight step across the footprint: " + p);
        for (NavPoint q : p) {
            if (q.x() < 8) {
                assertEquals(15.5, q.z(), 0.6, "on the road before the plaza: " + q);
            }
        }
    }

    @Test
    void aJunctionOfNarrowStreetsIsNoPlaza() {
        // two 3-wide streets crossing between houses
        world.walls(-6, 13, 13, 13).walls(17, 13, 36, 13).walls(-6, 17, 13, 17).walls(17, 17, 36, 17);
        world.walls(13, -6, 13, 13).walls(17, -6, 17, 13).walls(13, 17, 13, 36).walls(17, 17, 17, 36);
        long[] reads = new long[1];
        assertTrue(PlazaLine.openRadius(world, world.feet(15.5, 15.5), 16, reads) <= 4);
        assertTrue(reads[0] < 800, "a few rays, no plaza-sized corridor: " + reads[0]);

        CorridorShaper.ShapedLine shaped = shape(vLine(), PLAZAS, 0);
        CorridorShaper.ShapedLine m2 = new CorridorShaper(tick::get, null).shape(world, vLine(),
            indexes(vLine().size()), M2, true);
        assertEquals(0, shaped.plazaLines());
        assertEquals(m2.waypoints(), shaped.waypoints(), "as M2 shapes it");
    }

    @Test
    void plazaLinesAloneLeaveTheRestOfTheStretchAsItWas() {
        plaza();
        List<NavPoint> feet = vLine();
        CorridorShaper.ShapedLine shaped = shaper.shape(world, feet, indexes(feet.size()),
            CorridorShaper.Options.plazasOnly(4, 0.8, 16, 0.8), true, List.of(junction(0)));

        assertEquals(1, shaped.plazaLines());
        assertEquals(0, shaped.chokepoints());
        int kept = 0;
        for (NavPoint q : feet) {
            boolean near = Math.hypot(q.x() - 15.5, q.z() - 15.5) <= 8.2;
            if (!near) {
                assertTrue(shaped.waypoints().contains(q), "kept as it was off the plaza: " + q);
                kept++;
            }
        }
        assertTrue(kept > 4);
        double[] wc = walkedAndChord(shaped.waypoints());
        assertTrue(wc[0] <= wc[1] * 1.05, wc[0] + " vs " + wc[1]);
        // indexes stay in order
        List<Integer> ix = shaped.lineIndexes();
        for (int k = 1; k < ix.size(); k++) {
            assertTrue(ix.get(k) >= ix.get(k - 1), "in order: " + ix);
        }
    }

    @Test
    void theCostIsBoundedAndASecondNpcReadsNothing() {
        plaza();
        well();
        List<NavPoint> feet = vLine();
        shape(feet, PLAZAS, 0);
        long reads = shaper.reads();
        assertTrue(reads > 0 && reads < 60_000, "reads per stretch: " + reads);
        shape(feet, PLAZAS, 0);
        assertEquals(reads, shaper.reads(), "the second NPC on the stretch shares it");
        assertEquals(1, shaper.shared());
        assertEquals(1, shaper.plazaLines());

        Corridor c = Corridor.build(world, feet, 4, false, List.of(new Corridor.Disc(15.5, 15.5, 16)));
        PlazaLine.Result r = PlazaLine.search(c, new PlazaLine.Point(7.5, 15.5, 64),
            new PlazaLine.Point(15.5, 7.5, 64), new Corridor.Disc(15.5, 15.5, 9), 0.8);
        assertTrue(r.found());
        assertTrue(r.expanded() < 400, "cells expanded: " + r.expanded());
    }

    @Test
    void theOpenRadiusOfTheScenariosPlazaIsItsHalfSize() {
        plaza();
        well();
        double r = PlazaLine.openRadius(world, world.feet(15.5, 15.5), 16, new long[1]);
        assertTrue(r >= 7 && r <= 9, "about the plaza's half size: " + r);
    }
}
