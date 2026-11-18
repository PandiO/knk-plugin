package net.knightsandkings.knk.paper.navigation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.navigation.PlazaLinePreviews;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.navigation.shape.NavWorld;
import net.knightsandkings.knk.core.navigation.shape.PlazaLine;
import net.knightsandkings.knk.core.navigation.shape.PlazaCurve;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.paper.roads.RoadMessages;
import net.knightsandkings.knk.paper.roads.RoadNetworkCache;
import net.knightsandkings.knk.paper.utils.ParticleDraw;

/**
 * {@code /knk road plazaline [<plaza or junction>]} (off-road navigation phase 7 step 1, DESIGN § 8.9, O16;
 * PandiO/knk-workspace#712): the plaza lines drawn for the staff member for {@value #SHOW_SECONDS} seconds, so the
 * developer saw them on real plazas (Cinix, Brink) before the trail used them (step 2 draws them on the trail with
 * {@code navigation.plaza-lines} on). Works with the flag on or off and changes nothing: no route, session or trail is
 * touched.
 *
 * <ol>
 *     <li>Without an argument: the junctions of the player's own route that may be plazas ({@code /navigate} first).</li>
 *     <li>With a node id or name: every pair of the roads that meet there.</li>
 * </ol>
 *
 * <p>Drawn: the plaza line (green for the curve, orange for the A* line round an obstacle), a rejected curve (red), the
 * route's own bend at the node (white) and the footprint's edge (light blue). Each line is also written to chat: its
 * length against the chord and the right angle, its least clearance and why a curve was rejected. Main thread.
 */
public final class PlazaLinePreview {

    /** How long the lines stay. */
    public static final int SHOW_SECONDS = 10;
    /** Ticks between two redraws. */
    static final long PERIOD_TICKS = 10;
    /** Blocks of each road before and after the node a pass through a named node takes (beyond any footprint). */
    static final double ROAD_REACH = 40;
    static final String USAGE = "/knk road plazaline [<plaza or junction: id or name>]";
    private static final double SPACING = 0.5;
    private static final double LIFT = 1.15;
    private static final Particle.DustOptions CURVE = new Particle.DustOptions(Color.fromRGB(0x4CD964), 1.0f);
    private static final Particle.DustOptions GUIDED = new Particle.DustOptions(Color.fromRGB(0xFF9500), 1.0f);
    private static final Particle.DustOptions REJECTED = new Particle.DustOptions(Color.fromRGB(0xFF3B30), 0.6f);
    private static final Particle.DustOptions ROUTE = new Particle.DustOptions(Color.fromRGB(0xFFFFFF), 0.5f);
    private static final Particle.DustOptions EDGE = new Particle.DustOptions(Color.fromRGB(0x5AC8FA), 0.5f);

    private final Plugin plugin;
    private final Supplier<RoadNetworkCache> cache;
    private final Supplier<NavigationService> navigation;
    private final Supplier<NavWorld> world;
    private final Function<String, PlazaCurve.Options> options;
    private final Map<UUID, BukkitTask> shown = new HashMap<>();

    /**
     * @param plugin     for the redraw task
     * @param cache      the road network (named nodes)
     * @param navigation the players' navigation (a player's own route), null while it is off
     * @param world      the live world the lines are checked on (blocks and WorldGuard regions)
     * @param options    the plaza-line options for a world: the NPC side's {@code corridor-half-width} and
     *                   {@code plaza-corridor-blocks} (O19), the walk trail's {@code wall-clearance}, and
     *                   {@code plaza-pull} and {@code plaza-guide-cost}
     */
    public PlazaLinePreview(Plugin plugin, Supplier<RoadNetworkCache> cache, Supplier<NavigationService> navigation,
                            Supplier<NavWorld> world, Function<String, PlazaCurve.Options> options) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
        this.world = Objects.requireNonNull(world, "world");
        this.options = Objects.requireNonNull(options, "options");
    }

    /** Runs the command for {@code player} with the words after {@code plazaline}. */
    public void run(Player player, String[] args) {
        List<PlazaLinePreviews.Pass> passes = passes(player, args);
        if (passes == null) {
            return;
        }
        String worldName = player.getWorld().getName();
        PlazaCurve.Options opts = options.apply(worldName);
        List<PlazaLinePreviews.Line> lines = PlazaLinePreviews.compute(world.get(), passes, opts);
        player.sendMessage(RoadMessages.info(String.format(java.util.Locale.ROOT,
            "Plaza lines (preview; the trail draws them with navigation.plaza-lines on; plaza-pull %.2f, "
                + "curve clearance %.2f, A* clearance %.2f):", opts.pull(), opts.curveClearance(), opts.clearance())));
        for (PlazaLinePreviews.Line line : lines) {
            player.sendMessage(RoadMessages.field(kindLabel(line.result().kind()), PlazaLinePreviews.describe(line)));
        }
        show(player, lines);
    }

    /** The passes the arguments ask for; null after telling the player why there are none. */
    List<PlazaLinePreviews.Pass> passes(Player player, String[] args) {
        String worldName = player.getWorld().getName();
        if (args.length == 0) {
            NavigationService service = navigation.get();
            Optional<NavigationService.RoutePlazas> route = service == null ? Optional.empty()
                : service.routePlazas(player);
            if (route.isEmpty()) {
                player.sendMessage(RoadMessages.bad("You have no route. /navigate somewhere across a plaza first, or "
                    + "name one: " + USAGE));
                return null;
            }
            NavigationService.RoutePlazas r = route.get();
            if (r.nodes().isEmpty()) {
                player.sendMessage(RoadMessages.warn("Your route passes no junction."));
                return null;
            }
            List<PlazaLinePreviews.Pass> out = new ArrayList<>();
            List<NavPoint> feet = PlazaLinePreviews.feetOf(r.world(), r.polyline());
            for (RoadNode node : r.nodes()) {
                out.add(new PlazaLinePreviews.Pass(nodeLabel(node) + " on your route", feet,
                    PlazaLinePreviews.crossingOf(r.world(), node)));
            }
            return out;
        }
        RoadNetworkCache c = cache.get();
        RoadNetworkSnapshot snapshot = c == null ? null : c.snapshot(worldName);
        if (snapshot == null || snapshot.isEmpty()) {
            player.sendMessage(RoadMessages.bad("No road network is loaded for " + worldName + "."));
            return null;
        }
        String wanted = String.join(" ", args).trim();
        if (wanted.length() >= 2 && wanted.startsWith("\"") && wanted.endsWith("\"")) {
            wanted = wanted.substring(1, wanted.length() - 1);
        }
        Location at = player.getLocation();
        Optional<RoadNode> node = PlazaLinePreviews.findNode(snapshot, wanted, at.getX(), at.getZ());
        if (node.isEmpty()) {
            player.sendMessage(RoadMessages.bad("No node with the id or name \"" + wanted + "\" in " + worldName + "."));
            return null;
        }
        List<PlazaLinePreviews.Pass> out = PlazaLinePreviews.throughNode(snapshot, node.get(), ROAD_REACH);
        if (out.isEmpty()) {
            player.sendMessage(RoadMessages.warn(nodeLabel(node.get()) + " has fewer than two roads: no plaza line."));
            return null;
        }
        return out;
    }

    /** Draws the lines for the player every {@link #PERIOD_TICKS} for {@link #SHOW_SECONDS} seconds. */
    void show(Player player, List<PlazaLinePreviews.Line> lines) {
        BukkitTask previous = shown.remove(player.getUniqueId());
        if (previous != null) {
            previous.cancel();
        }
        long rounds = SHOW_SECONDS * 20L / PERIOD_TICKS;
        long[] done = {0};
        BukkitTask[] self = new BukkitTask[1];
        self[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || done[0]++ >= rounds) {
                self[0].cancel();
                shown.remove(player.getUniqueId(), self[0]);
                return;
            }
            draw(player, lines);
        }, 0L, PERIOD_TICKS);
        shown.put(player.getUniqueId(), self[0]);
    }

    /** One redraw of every line. */
    static void draw(Player player, List<PlazaLinePreviews.Line> lines) {
        for (PlazaLinePreviews.Line line : lines) {
            PlazaCurve.Result r = line.result();
            if (r.radius() <= 0) {
                continue; // a street junction or a route that does not cross: nothing to show
            }
            NavPoint centre = r.centre();
            ParticleDraw.polyline(player, routeBend(line), SPACING, Particle.DUST, ROUTE);
            ring(player, centre, r.radius(), (int) Math.floor(centre.y()));
            if (r.found()) {
                ParticleDraw.polyline(player, vectors(r.line()), SPACING,
                    Particle.DUST, r.kind() == PlazaCurve.Kind.CURVE ? CURVE : GUIDED);
            }
            if (r.curveFailure() != null && !r.curve().isEmpty()) {
                ParticleDraw.polyline(player, vectors(r.curve()), 1.0, Particle.DUST, REJECTED);
            }
        }
    }

    /** The route's own points inside the footprint (the bend at the node), lifted onto the floor. */
    static List<Vector> routeBend(PlazaLinePreviews.Line line) {
        PlazaCurve.Result r = line.result();
        List<Vector> out = new ArrayList<>();
        for (NavPoint p : line.pass().feet()) {
            if (Math.hypot(p.x() - r.centre().x(), p.z() - r.centre().z()) <= r.radius() + 0.5) {
                out.add(new Vector(p.x(), Math.floor(p.y()) - 1 + LIFT, p.z()));
            } else if (!out.isEmpty()) {
                break; // past the plaza
            }
        }
        return out;
    }

    private static void ring(Player player, NavPoint centre, double radius, int floor) {
        int points = Math.max(16, (int) Math.ceil(radius * 4));
        List<Vector> ring = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            ring.add(new Vector(centre.x() + radius * Math.cos(a), floor + LIFT,
                centre.z() + radius * Math.sin(a)));
        }
        ParticleDraw.points(player, ring, Particle.DUST, EDGE);
    }

    static List<Vector> vectors(List<PlazaLine.Point> points) {
        List<Vector> out = new ArrayList<>(points.size());
        for (PlazaLine.Point p : points) {
            out.add(new Vector(p.x(), p.floor() + LIFT, p.z()));
        }
        return out;
    }

    private static String kindLabel(PlazaCurve.Kind kind) {
        return switch (kind) {
            case CURVE -> "curve";
            case GUIDED -> "A* line";
            case NONE -> "no line";
            case STREET -> "street";
            case NOT_CROSSED -> "not crossed";
        };
    }

    private static String nodeLabel(RoadNode node) {
        return node.name() == null || node.name().isBlank() ? "node #" + node.id() : node.name() + " (#" + node.id() + ")";
    }

    /** How many players see a preview now (for tests). */
    int showing() {
        return shown.size();
    }
}
