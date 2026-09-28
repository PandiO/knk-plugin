package net.knightsandkings.knk.paper.roads;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.SnapPoint;
import net.knightsandkings.knk.core.roads.route.Snapper;
import net.knightsandkings.knk.paper.utils.ParticleDraw;
import net.kyori.adventure.text.Component;

/**
 * {@code /knk road show [radius] [all]} / {@code hide} (DESIGN §7): per admin, every 20 ticks, the road
 * network around them as particles - edges as dust polylines coloured by street/status
 * ({@link OverlayColors}), nodes as pillars by kind, a yellow marker where an edge crosses a gate. Only
 * edges within ±{@link #LEVEL_RANGE} blocks of the viewer's height unless {@code all}. The action bar
 * names the node or edge the admin looks at. Drawing goes through {@link ParticleDraw} (R9), per viewer.
 * Geometry y is the floor block (Phase 2c decision 1); particles float {@link #PARTICLE_LIFT} above it.
 */
public final class RoadOverlayRenderer {
    public static final long PERIOD_TICKS = 20L;
    public static final int DEFAULT_RADIUS = 48;
    public static final int MAX_RADIUS = 128;
    public static final int LEVEL_RANGE = 8;
    public static final double PARTICLE_LIFT = 1.1;
    public static final double EDGE_SPACING = 1.0;
    public static final double LOOK_DISTANCE = 12;

    /** One admin's overlay settings. */
    public record View(int radius, boolean allLevels) {
    }

    private final Plugin plugin;
    private final Function<String, RoadNetworkSnapshot> snapshots;
    private final Map<UUID, View> viewers = new ConcurrentHashMap<>();
    private BukkitTask ticker;

    public RoadOverlayRenderer(Plugin plugin, Function<String, RoadNetworkSnapshot> snapshots) {
        this.plugin = plugin;
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    // ===== per admin =====

    public void show(Player player, int radius, boolean allLevels) {
        viewers.put(player.getUniqueId(), new View(Math.max(4, Math.min(MAX_RADIUS, radius)), allLevels));
        ensureTicker();
    }

    public boolean hide(Player player) {
        boolean was = viewers.remove(player.getUniqueId()) != null;
        if (viewers.isEmpty()) {
            stop();
        }
        return was;
    }

    public Optional<View> viewOf(Player player) {
        return Optional.ofNullable(viewers.get(player.getUniqueId()));
    }

    public boolean isShowing(Player player) {
        return viewers.containsKey(player.getUniqueId());
    }

    public int viewerCount() {
        return viewers.size();
    }

    // ===== lifecycle =====

    private void ensureTicker() {
        if (ticker == null) {
            ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, PERIOD_TICKS);
        }
    }

    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    /** Clears every view (shutdown). */
    public void clear() {
        viewers.clear();
        stop();
    }

    // ===== drawing =====

    void tick() {
        for (Map.Entry<UUID, View> entry : viewers.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                viewers.remove(entry.getKey());
                continue;
            }
            RoadNetworkSnapshot snapshot = snapshots.apply(player.getWorld().getName());
            if (snapshot == null || snapshot.isEmpty()) {
                player.sendActionBar(Component.text("No roads built in this world yet", RoadMessages.INFO));
                continue;
            }
            draw(player, snapshot, entry.getValue());
        }
        if (viewers.isEmpty()) {
            stop();
        }
    }

    private void draw(Player viewer, RoadNetworkSnapshot snapshot, View view) {
        Location at = viewer.getLocation();
        double vx = at.getX();
        double vy = at.getY();
        double vz = at.getZ();
        for (RoadEdge edge : edgesNear(snapshot, vx, vy, vz, view)) {
            int rgb = OverlayColors.edge(edge);
            Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(rgb), 1.0f);
            ParticleDraw.polyline(viewer, lifted(edge.geometry()), EDGE_SPACING, Particle.DUST, dust);
            if (OverlayColors.hasGateMarker(edge)) {
                int[] mid = edge.geometry().get(edge.geometry().size() / 2);
                ParticleDraw.pillar(viewer, new Vector(mid[0] + 0.5, mid[1] + PARTICLE_LIFT, mid[2] + 0.5), 2.5, 0.5,
                    Particle.DUST, new Particle.DustOptions(Color.fromRGB(OverlayColors.GATE_MARKER), 1.4f));
            }
        }
        for (RoadNode node : snapshot.nodes()) {
            if (!within(node.x(), node.y(), node.z(), vx, vy, vz, view)) {
                continue;
            }
            int rgb = OverlayColors.node(node.kind(), node.isDestination());
            ParticleDraw.pillar(viewer, new Vector(node.x() + 0.5, node.y() + PARTICLE_LIFT, node.z() + 0.5),
                node.isDestination() ? 3.0 : 2.0, 0.5, Particle.DUST, new Particle.DustOptions(Color.fromRGB(rgb), 1.2f));
        }
        lookedAt(viewer, snapshot).ifPresent(text -> viewer.sendActionBar(Component.text(text, RoadMessages.HIGHLIGHT)));
    }

    /** Edges with at least one geometry point within the view's radius (and level range unless {@code all}). */
    static List<RoadEdge> edgesNear(RoadNetworkSnapshot snapshot, double vx, double vy, double vz, View view) {
        List<RoadEdge> near = new ArrayList<>();
        double r2 = (double) view.radius() * view.radius();
        for (RoadEdge edge : snapshot.edges()) {
            for (int[] p : edge.geometry()) {
                double dx = p[0] + 0.5 - vx;
                double dz = p[2] + 0.5 - vz;
                if (dx * dx + dz * dz > r2) {
                    continue;
                }
                if (!view.allLevels() && Math.abs(p[1] + 1 - vy) > LEVEL_RANGE) {
                    continue;
                }
                near.add(edge);
                break;
            }
        }
        return near;
    }

    static boolean within(int x, int y, int z, double vx, double vy, double vz, View view) {
        double dx = x + 0.5 - vx;
        double dz = z + 0.5 - vz;
        if (dx * dx + dz * dz > (double) view.radius() * view.radius()) {
            return false;
        }
        return view.allLevels() || Math.abs(y + 1 - vy) <= LEVEL_RANGE;
    }

    private static List<Vector> lifted(List<int[]> geometry) {
        List<Vector> points = new ArrayList<>(geometry.size());
        for (int[] p : geometry) {
            points.add(new Vector(p[0] + 0.5, p[1] + PARTICLE_LIFT, p[2] + 0.5));
        }
        return points;
    }

    /** The node or edge under the point {@link #LOOK_DISTANCE} blocks along the admin's view direction. */
    private Optional<String> lookedAt(Player viewer, RoadNetworkSnapshot snapshot) {
        Location eye = viewer.getEyeLocation();
        Vector dir = eye.getDirection();
        double px = eye.getX() + dir.getX() * LOOK_DISTANCE;
        double py = eye.getY() + dir.getY() * LOOK_DISTANCE;
        double pz = eye.getZ() + dir.getZ() * LOOK_DISTANCE;
        return describeAt(snapshot, px, py, pz);
    }

    /** Package-private for tests: the label for the network element nearest to a point (within 4 blocks), if any. */
    static Optional<String> describeAt(RoadNetworkSnapshot snapshot, double px, double py, double pz) {
        RoadNode nearest = null;
        double best = 3.0;
        for (RoadNode node : snapshot.nodes()) {
            double d = node.distanceTo(px, py - 1, pz);
            if (d < best) {
                best = d;
                nearest = node;
            }
        }
        if (nearest != null) {
            return Optional.of("Node #" + nearest.id() + " " + nearest.kind().apiName().toLowerCase()
                + nearest.nameOptional().map(n -> " \"" + n + "\"").orElse("")
                + (nearest.locked() ? " (locked)" : ""));
        }
        Optional<SnapPoint> snap = Snapper.snap(snapshot, px, py - 1, pz, 4.0, 2.0);
        if (snap.isEmpty()) {
            return Optional.empty();
        }
        RoadEdge edge = snapshot.requireEdge(snap.get().edgeId());
        StringBuilder text = new StringBuilder("Edge #").append(edge.id());
        snapshot.streetOf(edge).ifPresentOrElse(s -> text.append(" ").append(s), () -> text.append(" (unlabelled)"));
        snapshot.roadClass(edge).ifPresent(c -> text.append(" · ").append(c.apiName()));
        text.append(" · ").append(RoadMessages.distance(edge.length()));
        OverlayColors.status(edge).ifPresent(s -> text.append(" · ").append(s));
        if (!edge.gateDoorIds().isEmpty()) {
            text.append(" · gate ").append(edge.gateDoorIds());
        }
        return Optional.of(text.toString());
    }
}
