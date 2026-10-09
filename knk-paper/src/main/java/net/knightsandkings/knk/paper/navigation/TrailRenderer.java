package net.knightsandkings.knk.paper.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import net.knightsandkings.knk.core.roads.route.Route;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.utils.KnkLocations;
import net.knightsandkings.knk.paper.utils.ParticleDraw;
import net.knightsandkings.knk.paper.utils.TickBudget;

/**
 * The particle trail (DESIGN §6.4): every {@code trail-period-ticks} the next {@code trail-length}
 * blocks of the route from the player's projection, one particle every {@link #SPACING} blocks at
 * standing height + 0.2, through {@link ParticleDraw#polyline} (R9) so only the navigating player
 * sees it. Off-road legs - the player to the road while they are not on it yet, and the road's end
 * to the destination - are straight, sparser ({@link #LEG_SPACING}) and in another colour, with
 * their heights snapped to the floor ({@link KnkLocations#floorOf}, R28). Under lag
 * ({@link TickBudget}) the trail is drawn at half length. The point maths is pure for the tests.
 */
public final class TrailRenderer {

    public static final double SPACING = 1.5;
    public static final double LEG_SPACING = 3.0;
    /** Particles float this much above the floor block's top (standing height + 0.2). */
    public static final double LIFT = 1.2;
    /** An off-road leg is drawn when the gap it bridges is longer than this. */
    public static final double LEG_MIN = 2.0;
    public static final int LEG_COLOR = 0x8FD3FF;

    private final NavigationConfig.TrailConfig config;
    private final TickBudget budget;
    private final Particle particle;
    private final Object routeData;
    private final Object legData;

    public TrailRenderer(NavigationConfig.TrailConfig config, TickBudget budget) {
        this.config = Objects.requireNonNull(config, "config");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.particle = particleOf(config.particle());
        this.routeData = particle == Particle.DUST ? new Particle.DustOptions(Color.fromRGB(config.rgb()), 1.0f) : null;
        this.legData = particle == Particle.DUST ? new Particle.DustOptions(Color.fromRGB(LEG_COLOR), 0.8f) : null;
    }

    public int periodTicks() {
        return config.periodTicks();
    }

    /**
     * Draw the route trail for {@code viewer}: the next {@code trail-length} blocks from
     * {@code along}, plus the two off-road legs when needed. {@code target} is the real destination
     * point (floor coordinates; null when the route ends at it). Main thread.
     */
    public void drawRoute(Player viewer, Route route, double along, double[] target) {
        double length = budget.isLagging() ? Math.max(4, config.length() / 2.0) : config.length();
        List<double[]> trail = trailPoints(route, along, length, SPACING);
        if (trail.isEmpty()) {
            return;
        }
        Location feet = viewer.getLocation();
        double[] first = trail.get(0);
        double[] player = {feet.getX(), feet.getY() - 1, feet.getZ()};
        if (distance(player, first) > LEG_MIN) {
            drawLeg(viewer, player, first);
        }
        ParticleDraw.polyline(viewer, lifted(trail), SPACING, particle, routeData);
        if (target != null && along + length >= route.polylineLength()) {
            double[] end = route.end().point();
            if (distance(end, target) > LEG_MIN) {
                drawLeg(viewer, end, target);
            }
        }
    }

    /** Direct mode (DESIGN §6.2): a straight trail from the player to {@code target}. Main thread. */
    public void drawDirect(Player viewer, double[] target) {
        Location feet = viewer.getLocation();
        drawLeg(viewer, new double[] {feet.getX(), feet.getY() - 1, feet.getZ()}, target);
    }

    /**
     * Direct mode with a walk path (KNG-51 {@code LAST_MILE_PATHFINDING.md} §7): the next
     * {@code trail-length} blocks of {@code floorPoints} from the player's projection onto it (half
     * length under lag), in the leg colour and spacing. The points are floor cells already
     * ({@code WalkPath.points()}: block centre, floor y), so no world reads. Main thread.
     */
    public void drawPath(Player viewer, List<double[]> floorPoints) {
        double length = budget.isLagging() ? Math.max(4, config.length() / 2.0) : config.length();
        Location feet = viewer.getLocation();
        List<double[]> window = pathWindow(floorPoints, new double[] {feet.getX(), feet.getY() - 1, feet.getZ()},
            length, LEG_SPACING);
        if (window.isEmpty()) {
            return;
        }
        ParticleDraw.polyline(viewer, lifted(window), LEG_SPACING, particle, legData);
    }

    private void drawLeg(Player viewer, double[] from, double[] to) {
        List<double[]> points = legPoints(from, to, LEG_SPACING);
        List<Vector> lifted = new ArrayList<>(points.size());
        for (double[] p : points) {
            Location spot = KnkLocations.floorOf(new Location(viewer.getWorld(), p[0], p[1] + 1, p[2]));
            lifted.add(new Vector(p[0], spot.getY() + LIFT, p[2]));
        }
        ParticleDraw.polyline(viewer, lifted, LEG_SPACING, particle, legData);
    }

    // ==================== pure ====================

    /**
     * The route polyline sampled every {@code spacing} blocks from {@code along} for {@code length}
     * blocks (floor coordinates shifted to the block centre), ending with the window's last point.
     * Empty when the route is empty.
     */
    public static List<double[]> trailPoints(Route route, double along, double length, double spacing) {
        List<double[]> out = new ArrayList<>();
        if (route == null || route.isEmpty() || spacing <= 0) {
            return out;
        }
        double total = route.polylineLength();
        double start = Math.max(0, Math.min(along, total));
        double stop = Math.min(total, start + length);
        for (double a = start; a < stop; a += spacing) {
            out.add(centre(route.pointAt(a)));
        }
        out.add(centre(route.pointAt(stop)));
        return out;
    }

    /**
     * A floor-point polyline (a walk path) sampled every {@code spacing} blocks for {@code length}
     * blocks from the point nearest {@code from} ({@link #project}), ending with the window's last
     * point. Empty for an empty polyline.
     */
    public static List<double[]> pathWindow(List<double[]> points, double[] from, double length, double spacing) {
        List<double[]> out = new ArrayList<>();
        if (points == null || points.isEmpty() || spacing <= 0) {
            return out;
        }
        double total = polylineLength(points);
        double start = project(points, from)[0];
        double stop = Math.min(total, start + length);
        for (double a = start; a < stop; a += spacing) {
            out.add(pointAt(points, a));
        }
        out.add(pointAt(points, stop));
        return out;
    }

    /**
     * The point of the polyline nearest {@code p} (3D): {@code {along, distance}} — how far along the
     * polyline it lies and how far {@code p} is from it. A single point projects onto itself.
     */
    public static double[] project(List<double[]> points, double[] p) {
        double bestAlong = 0;
        double best = distance(points.get(0), p);
        double along = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double segment = distance(a, b);
            if (segment > 1e-9) {
                double t = ((p[0] - a[0]) * (b[0] - a[0]) + (p[1] - a[1]) * (b[1] - a[1]) + (p[2] - a[2]) * (b[2] - a[2]))
                    / (segment * segment);
                t = Math.max(0, Math.min(1, t));
                double[] q = {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
                double d = distance(q, p);
                if (d < best) {
                    best = d;
                    bestAlong = along + segment * t;
                }
            }
            along += segment;
        }
        return new double[] {bestAlong, best};
    }

    /** The point {@code along} blocks along the polyline (clamped to its ends). */
    public static double[] pointAt(List<double[]> points, double along) {
        double walked = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double segment = distance(a, b);
            if (segment > 1e-9 && walked + segment >= along) {
                double t = Math.max(0, (along - walked) / segment);
                return new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
            }
            walked += segment;
        }
        return points.get(points.size() - 1).clone();
    }

    /** Total 3D length of a polyline. */
    public static double polylineLength(List<double[]> points) {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            total += distance(points.get(i - 1), points.get(i));
        }
        return total;
    }

    /** {@code from} to {@code to} in a straight line, one point every {@code spacing} blocks, both ends included. */
    public static List<double[]> legPoints(double[] from, double[] to, double spacing) {
        List<double[]> out = new ArrayList<>();
        double d = distance(from, to);
        if (d < 1e-6 || spacing <= 0) {
            out.add(from.clone());
            return out;
        }
        int steps = (int) Math.floor(d / spacing);
        for (int i = 0; i <= steps; i++) {
            double t = i * spacing / d;
            out.add(new double[] {from[0] + (to[0] - from[0]) * t, from[1] + (to[1] - from[1]) * t, from[2] + (to[2] - from[2]) * t});
        }
        double[] last = out.get(out.size() - 1);
        if (distance(last, to) > 1e-6) {
            out.add(to.clone());
        }
        return out;
    }

    /** Floor points → particle positions: block centre, {@link #LIFT} above the floor block. */
    public static List<Vector> lifted(List<double[]> floorPoints) {
        List<Vector> out = new ArrayList<>(floorPoints.size());
        for (double[] p : floorPoints) {
            out.add(new Vector(p[0], p[1] + LIFT, p[2]));
        }
        return out;
    }

    /** Polyline points are floor-block coordinates: shift into the block's centre without quantising the spacing. */
    static double[] centre(double[] p) {
        return new double[] {p[0] + 0.5, p[1], p[2] + 0.5};
    }

    static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static Particle particleOf(String name) {
        try {
            return Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Particle.DUST;
        }
    }
}
