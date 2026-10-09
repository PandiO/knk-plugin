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
import net.knightsandkings.knk.core.roads.build.TileProposal;
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
    /** A node pillar or edge counts as looked at when the view ray passes this close (blocks). */
    public static final double RAY_TOLERANCE = 1.5;
    static final double RAY_STEP = 0.5;
    /** Heights above a node's floor block where its pillar is tested against the view ray. */
    private static final double[] PILLAR_SAMPLES = {PARTICLE_LIFT, PARTICLE_LIFT + 1, PARTICLE_LIFT + 2};

    /** One admin's overlay settings. */
    public record View(int radius, boolean allLevels) {
    }

    private final Plugin plugin;
    private final Function<String, RoadNetworkSnapshot> snapshots;
    private final Map<UUID, View> viewers = new ConcurrentHashMap<>();
    private BukkitTask ticker;
    /** Pending proposal items per tile of a world (plan §5.7); none until {@link #setProposals}. */
    private volatile Function<String, Map<TileKey, List<TileProposal.Item>>> proposals = world -> Map.of();

    public RoadOverlayRenderer(Plugin plugin, Function<String, RoadNetworkSnapshot> snapshots) {
        this.plugin = plugin;
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    /** Curated tiles (plan §5.7): also draw the pending proposal items of the viewer's world. */
    public void setProposals(Function<String, Map<TileKey, List<TileProposal.Item>>> proposals) {
        this.proposals = proposals == null ? world -> Map.of() : proposals;
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
        Map<TileKey, List<TileProposal.Item>> pending = proposals.apply(viewer.getWorld().getName());
        drawProposals(viewer, pending, vx, vy, vz, view);
        Optional<String> proposalLabel = lookedAtProposal(viewer, pending, view);
        if (proposalLabel.isPresent()) {
            viewer.sendActionBar(Component.text(proposalLabel.get(), RoadMessages.WARN));
            return;
        }
        lookedAt(viewer, snapshot, view).ifPresent(text -> viewer.sendActionBar(Component.text(text, RoadMessages.HIGHLIGHT)));
    }

    /** Proposal items near the viewer: added green, removed red, changed yellow, a moved node as a yellow line + pillar. */
    private static void drawProposals(Player viewer, Map<TileKey, List<TileProposal.Item>> pending, double vx, double vy, double vz, View view) {
        for (List<TileProposal.Item> items : pending.values()) {
            for (TileProposal.Item item : items) {
                if (!near(item, vx, vy, vz, view)) {
                    continue;
                }
                Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(proposalColour(item.kind())), 1.5f);
                switch (item.kind()) {
                    case EDGE_ADDED, EDGE_REMOVED, EDGE_CHANGED ->
                        ParticleDraw.polyline(viewer, lifted(item.geometry()), EDGE_SPACING / 2, Particle.DUST, dust);
                    case NODE_MOVED -> {
                        int[] from = item.node().position();
                        ParticleDraw.polyline(viewer, lifted(List.of(from, item.target())), EDGE_SPACING / 2, Particle.DUST, dust);
                        ParticleDraw.pillar(viewer, new Vector(item.target()[0] + 0.5, item.target()[1] + PARTICLE_LIFT, item.target()[2] + 0.5),
                            3.0, 0.5, Particle.DUST, dust);
                    }
                    case NODE_REMOVED -> {
                        int[] at = item.node().position();
                        ParticleDraw.pillar(viewer, new Vector(at[0] + 0.5, at[1] + PARTICLE_LIFT, at[2] + 0.5), 3.0, 0.5, Particle.DUST, dust);
                    }
                }
            }
        }
    }

    static int proposalColour(TileProposal.Kind kind) {
        return switch (kind) {
            case EDGE_ADDED -> OverlayColors.PROPOSAL_ADDED;
            case EDGE_REMOVED, NODE_REMOVED -> OverlayColors.PROPOSAL_REMOVED;
            case EDGE_CHANGED, NODE_MOVED -> OverlayColors.PROPOSAL_CHANGED;
        };
    }

    private static boolean near(TileProposal.Item item, double vx, double vy, double vz, View view) {
        for (int[] p : points(item)) {
            if (within(p[0], p[1], p[2], vx, vy, vz, view)) {
                return true;
            }
        }
        return false;
    }

    private static List<int[]> points(TileProposal.Item item) {
        if (item.kind().isEdge()) {
            return item.geometry();
        }
        return item.kind() == TileProposal.Kind.NODE_MOVED ? List.of(item.node().position(), item.target()) : List.of(item.node().position());
    }

    private Optional<String> lookedAtProposal(Player viewer, Map<TileKey, List<TileProposal.Item>> pending, View view) {
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        Location eye = viewer.getEyeLocation();
        Vector dir = eye.getDirection();
        return describeLookedAtProposal(pending, new double[] {eye.getX(), eye.getY(), eye.getZ()},
            new double[] {dir.getX(), dir.getY(), dir.getZ()}, view.radius());
    }

    /**
     * Package-private for tests: the proposal item whose drawn points pass closest to the view ray (within
     * {@link #RAY_TOLERANCE}, up to {@code maxDistance} ahead), as "Proposal 2,-2 · 3 added edge …" - particles carry
     * no text, so the action bar gives the item number the review commands take.
     */
    static Optional<String> describeLookedAtProposal(Map<TileKey, List<TileProposal.Item>> pending, double[] eye, double[] dir,
                                                     double maxDistance) {
        double norm = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
        if (norm == 0) {
            return Optional.empty();
        }
        double[] d = {dir[0] / norm, dir[1] / norm, dir[2] / norm};
        String best = null;
        double bestOff = RAY_TOLERANCE;
        double bestAlong = Double.MAX_VALUE;
        for (Map.Entry<TileKey, List<TileProposal.Item>> entry : pending.entrySet()) {
            for (TileProposal.Item item : entry.getValue()) {
                for (int[] p : densified(points(item))) {
                    double[] q = {p[0] + 0.5, p[1] + PARTICLE_LIFT, p[2] + 0.5};
                    double along = (q[0] - eye[0]) * d[0] + (q[1] - eye[1]) * d[1] + (q[2] - eye[2]) * d[2];
                    if (along < 0 || along > maxDistance) {
                        continue;
                    }
                    double ox = q[0] - (eye[0] + d[0] * along);
                    double oy = q[1] - (eye[1] + d[1] * along);
                    double oz = q[2] - (eye[2] + d[2] * along);
                    double off = Math.sqrt(ox * ox + oy * oy + oz * oz);
                    if (off < bestOff - 1e-9 || (Math.abs(off - bestOff) <= 1e-9 && along < bestAlong)) {
                        bestOff = off;
                        bestAlong = along;
                        best = "Proposal " + RoadProposals.label(entry.getKey()) + " · " + item.describe();
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** The polyline's points plus one every block between them (the ray test needs the segments, not only the corners). */
    private static List<int[]> densified(List<int[]> points) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            int[] a = points.get(i);
            out.add(a);
            if (i + 1 < points.size()) {
                int[] b = points.get(i + 1);
                int steps = (int) Math.ceil(Math.sqrt(Math.pow(b[0] - a[0], 2) + Math.pow(b[1] - a[1], 2) + Math.pow(b[2] - a[2], 2)));
                for (int s = 1; s < steps; s++) {
                    double t = (double) s / steps;
                    out.add(new int[] {(int) Math.round(a[0] + t * (b[0] - a[0])), (int) Math.round(a[1] + t * (b[1] - a[1])),
                        (int) Math.round(a[2] + t * (b[2] - a[2]))});
                }
            }
        }
        return out;
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

    /** The node or edge the admin looks at (smoke test finding G), see {@link #describeLookedAt}. */
    private Optional<String> lookedAt(Player viewer, RoadNetworkSnapshot snapshot, View view) {
        Location eye = viewer.getEyeLocation();
        Vector dir = eye.getDirection();
        Location feet = viewer.getLocation();
        return describeLookedAt(snapshot, new double[] {eye.getX(), eye.getY(), eye.getZ()},
            new double[] {dir.getX(), dir.getY(), dir.getZ()}, new double[] {feet.getX(), feet.getY(), feet.getZ()},
            view.radius());
    }

    /**
     * Package-private for tests: what the action bar names (smoke test finding G - it used to test one
     * point exactly {@code LOOK_DISTANCE} blocks along the view, so nodes nearer or farther, or seen
     * from above, were never named). In order: the node whose pillar passes closest to the view ray
     * (within {@link #RAY_TOLERANCE}, up to {@code maxDistance} ahead; nearer wins a tie); else the
     * first edge the ray passes over; else the node the {@code here} commands act on (nearest the
     * feet within {@link RoadAdminCommand#HERE_DISTANCE}), marked "(here)".
     *
     * @param eye  eye position
     * @param dir  view direction (any length)
     * @param feet feet position
     */
    static Optional<String> describeLookedAt(RoadNetworkSnapshot snapshot, double[] eye, double[] dir, double[] feet,
                                            double maxDistance) {
        double norm = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
        if (norm > 0) {
            double[] d = {dir[0] / norm, dir[1] / norm, dir[2] / norm};
            RoadNode best = null;
            double bestOff = RAY_TOLERANCE;
            double bestAlong = Double.MAX_VALUE;
            for (RoadNode node : snapshot.nodes()) {
                for (double lift : PILLAR_SAMPLES) {
                    double[] q = {node.x() + 0.5, node.y() + lift, node.z() + 0.5};
                    double along = (q[0] - eye[0]) * d[0] + (q[1] - eye[1]) * d[1] + (q[2] - eye[2]) * d[2];
                    if (along < 0 || along > maxDistance) {
                        continue;
                    }
                    double ox = q[0] - (eye[0] + d[0] * along);
                    double oy = q[1] - (eye[1] + d[1] * along);
                    double oz = q[2] - (eye[2] + d[2] * along);
                    double off = Math.sqrt(ox * ox + oy * oy + oz * oz);
                    if (off < bestOff - 1e-9 || (Math.abs(off - bestOff) <= 1e-9 && along < bestAlong)) {
                        bestOff = off;
                        bestAlong = along;
                        best = node;
                    }
                }
            }
            if (best != null) {
                return Optional.of(nodeLabel(best));
            }
            for (double along = 1; along <= maxDistance; along += RAY_STEP) {
                double x = eye[0] + d[0] * along - 0.5;
                double y = eye[1] + d[1] * along - PARTICLE_LIFT;
                double z = eye[2] + d[2] * along - 0.5;
                Optional<SnapPoint> snap = Snapper.snap(snapshot, x, y, z, RAY_TOLERANCE, 1.0);
                if (snap.isPresent()) {
                    return Optional.of(edgeLabel(snapshot, snapshot.requireEdge(snap.get().edgeId())));
                }
            }
        }
        RoadNode here = null;
        double hereDistance = RoadAdminCommand.HERE_DISTANCE;
        for (RoadNode node : snapshot.nodes()) {
            double distance = node.distanceTo(feet[0] - 0.5, feet[1] - 1, feet[2] - 0.5);
            if (distance < hereDistance) {
                hereDistance = distance;
                here = node;
            }
        }
        return here == null ? Optional.empty() : Optional.of(nodeLabel(here) + " (here)");
    }

    static String nodeLabel(RoadNode node) {
        String kind = node.kind() == net.knightsandkings.knk.core.domain.roads.RoadNodeKind.PRUNED_EDGE
            ? "pruned edge" : node.kind().apiName().toLowerCase();
        return "Node #" + node.id() + " " + kind
            + node.nameOptional().map(n -> " \"" + n + "\"").orElse("")
            + (node.isPlazaCentre() ? " · plaza r" + node.plazaRadius() : "")
            + (node.locked() && !node.kind().isTombstone() ? " (locked)" : "");
    }

    static String edgeLabel(RoadNetworkSnapshot snapshot, RoadEdge edge) {
        StringBuilder text = new StringBuilder("Edge #").append(edge.id());
        snapshot.streetOf(edge).ifPresentOrElse(s -> text.append(" ").append(s), () -> text.append(" (unlabelled)"));
        snapshot.roadClass(edge).ifPresent(c -> text.append(" · ").append(c.apiName()));
        text.append(" · ").append(RoadMessages.distance(edge.length()));
        OverlayColors.status(edge).ifPresent(st -> text.append(" · ").append(st));
        if (!edge.gateDoorIds().isEmpty()) {
            text.append(" · gate ").append(edge.gateDoorIds());
        }
        return text.toString();
    }
}
