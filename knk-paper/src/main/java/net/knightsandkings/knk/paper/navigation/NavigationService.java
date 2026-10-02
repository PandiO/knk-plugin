package net.knightsandkings.knk.paper.navigation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.navigation.NavigationEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.ArrivedEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.ComputeRouteEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.EndReason;
import net.knightsandkings.knk.core.navigation.NavigationEffect.EndedEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.GuidanceEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.RerouteStartedEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.RouteAdoptedEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.RouteKeptEffect;
import net.knightsandkings.knk.core.navigation.NavigationEffect.RouteReason;
import net.knightsandkings.knk.core.navigation.NavigationSession;
import net.knightsandkings.knk.core.navigation.SessionParameters;
import net.knightsandkings.knk.core.roads.route.AStarRouter;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.BlockedExplainer;
import net.knightsandkings.knk.core.roads.route.EdgePolyline;
import net.knightsandkings.knk.core.roads.route.EdgeVerdict;
import net.knightsandkings.knk.core.roads.route.EtaEstimator;
import net.knightsandkings.knk.core.roads.route.Maneuver;
import net.knightsandkings.knk.core.roads.route.ManeuverBuilder;
import net.knightsandkings.knk.core.roads.route.RegionClosestPoint;
import net.knightsandkings.knk.core.roads.route.RegionShape;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.roads.route.Route;
import net.knightsandkings.knk.core.roads.route.RouteRequest;
import net.knightsandkings.knk.core.roads.route.RouteResult;
import net.knightsandkings.knk.core.roads.route.RouterParameters;
import net.knightsandkings.knk.core.roads.route.SnapPoint;
import net.knightsandkings.knk.core.roads.route.Snapper;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.events.NavigationArriveEvent;
import net.knightsandkings.knk.paper.events.NavigationEndEvent;
import net.knightsandkings.knk.paper.events.NavigationRerouteEvent;
import net.knightsandkings.knk.paper.events.NavigationStartEvent;
import net.knightsandkings.knk.paper.siege.SiegeLobbyRuntime;
import net.knightsandkings.knk.paper.siege.SiegeMatch;
import net.knightsandkings.knk.paper.siege.SiegeMatchObserver;
import net.kyori.adventure.text.Component;

/**
 * {@code /navigate} at runtime (DESIGN §6.2-6.7, plan Phase 4 tasks 7-10): one
 * {@link NavigationSession} (2d's pure state machine) per player, routing on a routing thread,
 * every effect applied on the main thread (R12), the trail and HUD per tick, and the live
 * re-route triggers - gate state changes (R4), siege lockdowns (R24), domain cache refreshes,
 * network snapshot swaps and the D13 safety net (every {@link #RECHECK_TICKS} ticks the gate and
 * domain verdicts of each active route are re-evaluated).
 *
 * <p><b>Direct mode</b> (DESIGN §6.2): a target within {@code max-snap-distance} of the player
 * gets a straight trail and no road; a routed session whose road ends short of the real target
 * switches to it on arrival at the road's end (the last off-road leg). A region destination
 * prefers the road ({@link #regionGoals}). Direct mode has its own periodic re-check
 * ({@link #recheckDirect}).
 *
 * <p>All state lives on the main thread; the routing thread only ever sees an immutable snapshot,
 * a policy built on the main thread and the request, and posts its result back.
 */
public final class NavigationService implements SiegeMatchObserver {

    /** The per-player {@link AccessPolicy} builder (paper: {@link NavigationAccess}). */
    @FunctionalInterface
    public interface PolicyFactory {
        AccessPolicy policyFor(Player player, RoadNetworkSnapshot snapshot);
    }

    /** Everything the service needs, so tests can fake the server. */
    public record Deps(Plugin plugin, NavigationConfig config, Function<String, RoadNetworkSnapshot> snapshots,
                       PolicyFactory policies, RegionShapes regionShapes, NavigationEligibility eligibility,
                       NavigationHud hud, TrailRenderer trail, Executor mainThread, Executor routing, LongSupplier tick,
                       Consumer<Event> events, Logger logger) {
        public Deps {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(snapshots, "snapshots");
            Objects.requireNonNull(policies, "policies");
            Objects.requireNonNull(regionShapes, "regionShapes");
            Objects.requireNonNull(eligibility, "eligibility");
            Objects.requireNonNull(hud, "hud");
            Objects.requireNonNull(trail, "trail");
            Objects.requireNonNull(mainThread, "mainThread");
            Objects.requireNonNull(routing, "routing");
            Objects.requireNonNull(tick, "tick");
            Objects.requireNonNull(events, "events");
            logger = logger == null ? Logger.getLogger(NavigationService.class.getName()) : logger;
        }
    }

    /** D13: how often the verdicts of an active route are re-checked (2 s). */
    public static final int RECHECK_TICKS = 40;
    /** How often the boss bar and action bar refresh. */
    public static final int HUD_TICKS = 10;
    /** The next maneuver is announced (chat, boss bar) within this many blocks (DESIGN §6.4). */
    public static final double MANEUVER_ANNOUNCE_DISTANCE = 20;
    /** A teleport farther than this ends the session (DESIGN §6.4). */
    public static final double TELEPORT_END_DISTANCE = 16;
    /**
     * A region at most this far away is walked to straight (direct mode) even when a road is nearer:
     * a few steps don't justify a detour to where the road enters it (fix plan 5.5 item 2).
     */
    static final double REGION_DIRECT_DISTANCE = 8;
    /** Every street edge is sampled this often to find its point nearest the player. */
    static final double STREET_SAMPLE_SPACING = 2;
    /** Arrival chime (DESIGN §6.4), as an Adventure sound so no Bukkit registry is touched. */
    static final net.kyori.adventure.sound.Sound ARRIVAL_SOUND = net.kyori.adventure.sound.Sound.sound(
        net.kyori.adventure.key.Key.key("entity.player.levelup"), net.kyori.adventure.sound.Sound.Source.PLAYER, 0.7f, 1.4f);

    private final Deps deps;
    private final RouterParameters routerParameters;
    private final SessionParameters sessionParameters;
    private final EtaEstimator eta;
    private final Map<UUID, Active> active = new HashMap<>();
    private BukkitTask ticker;

    /** One player's navigation: the core session plus what the runtime adds. */
    final class Active {
        final Player player;
        final Destination destination;
        final long startedTick;
        RoadNetworkSnapshot snapshot;
        NavigationSession session;
        List<SnapPoint> goals = List.of();
        /** The real target as a floor point (null for a region: the road's end is the arrival). */
        double[] target;
        RegionShape region;
        /** Direct mode's leg (DESIGN §6.2, KNG-51 §7); null while the session follows the road. */
        DirectLeg leg;
        int generation;
        int announcedManeuvers;
        boolean hintShown;
        long lastRecheckTick;

        Active(Player player, Destination destination, long startedTick) {
            this.player = player;
            this.destination = destination;
            this.startedTick = startedTick;
        }

        Optional<Route> route() {
            return session == null ? Optional.empty() : session.route();
        }

        boolean direct() {
            return leg != null;
        }
    }

    /**
     * Direct mode's state (KNG-51 {@code LAST_MILE_PATHFINDING.md} §7): the off-road leg to the
     * target, for a nearby target and for the last leg after a road's end alike. Owned by
     * {@link Active}; replaced, never reused, when direct mode starts again.
     */
    static final class DirectLeg {
        /** The leg's target as a floor point (re-derived by {@link #recheckDirect} for regions and streets). */
        double[] target;
        /** Distance at the last (re)draw, for the HUD's progress. */
        double total;
        /** The closest the player has come to the target since it was (re)drawn. */
        double best;
        long lastRecalcTick = Long.MIN_VALUE;

        DirectLeg(double[] target) {
            this.target = target;
        }
    }

    /** {@link #resolveGoals}'s answer: goals (or a direct target), or a refusal message. */
    record Goals(List<SnapPoint> goals, double[] target, RegionShape region, boolean direct, Component refusal,
                 boolean alreadyThere) {
        static Goals refused(Component message) {
            return new Goals(List.of(), null, null, false, message, false);
        }

        static Goals already() {
            return new Goals(List.of(), null, null, false, null, true);
        }

        boolean ok() {
            return refusal == null && !alreadyThere;
        }
    }

    public NavigationService(Deps deps) {
        this.deps = Objects.requireNonNull(deps, "deps");
        this.routerParameters = deps.config().routerParameters();
        this.sessionParameters = deps.config().sessionParameters();
        this.eta = new EtaEstimator(sessionParameters.sprintSpeed());
    }

    // ==================== lifecycle ====================

    /** Starts the per-tick guidance (R27 shape). */
    public void start() {
        if (deps.plugin() != null && ticker == null) {
            ticker = Bukkit.getScheduler().runTaskTimer(deps.plugin(), this::tickAll, 1L, 1L);
        }
    }

    /** Ends every session (shutdown) and stops the ticker. */
    public void stop() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        endAll(EndReason.STOPPED);
    }

    public boolean isNavigating(UUID playerId) {
        return active.containsKey(playerId);
    }

    public int activeCount() {
        return active.size();
    }

    public EtaEstimator eta() {
        return eta;
    }

    // ==================== commands ====================

    /** {@code /navigate} with no arguments: where to and how far, or the usage. Main thread. */
    public void status(Player player) {
        Active a = active.get(player.getUniqueId());
        if (a == null) {
            player.sendMessage(NavigationMessages.notNavigating());
            return;
        }
        player.sendMessage(NavigationMessages.status(a.destination.name(), remaining(a), eta));
    }

    /** {@code /navigate stop}: true when there was a session. Main thread. */
    public boolean stop(Player player) {
        Active a = active.get(player.getUniqueId());
        if (a == null) {
            return false;
        }
        end(a, EndReason.STOPPED);
        return true;
    }

    /** End the player's session for a runtime reason (quit, death, …); no-op without one. Main thread. */
    public void end(Player player, EndReason reason) {
        Active a = active.get(player.getUniqueId());
        if (a != null) {
            end(a, reason);
        }
    }

    public void endAll(EndReason reason) {
        for (Active a : new ArrayList<>(active.values())) {
            end(a, reason);
        }
    }

    /**
     * Start navigating {@code player} to {@code destination} (DESIGN §6.2): refuses when the player is
     * not eligible, no network is loaded, the player or the target is too far from a road, or the
     * player is already there; otherwise replaces any current session. Main thread.
     */
    public void navigate(Player player, Destination destination) {
        NavigationEligibility.Exclusion exclusion = deps.eligibility().exclusion(player);
        if (exclusion != NavigationEligibility.Exclusion.NONE) {
            player.sendMessage(switch (exclusion) {
                case LOADING -> NavigationMessages.loading();
                case FROZEN -> NavigationMessages.frozen();
                case SIEGE -> NavigationMessages.inSiege();
                case NONE -> NavigationMessages.disabled();
            });
            return;
        }
        String world = player.getWorld().getName();
        if (!destination.world().equalsIgnoreCase(world)) {
            player.sendMessage(NavigationMessages.otherWorld(destination.name()));
            return;
        }
        RoadNetworkSnapshot snapshot = deps.snapshots().apply(world);
        if (snapshot == null || snapshot.isEmpty()) {
            player.sendMessage(NavigationMessages.noNetworkHere(world));
            return;
        }
        Goals goals = resolveGoals(player, snapshot, destination);
        if (goals.alreadyThere()) {
            player.sendMessage(NavigationMessages.alreadyThere(destination.name()));
            return;
        }
        if (goals.refusal() != null) {
            player.sendMessage(goals.refusal());
            return;
        }
        NavigationStartEvent event = new NavigationStartEvent(player, destination.name());
        deps.events().accept(event);
        if (event.isCancelled()) {
            return;
        }
        Active previous = active.remove(player.getUniqueId());
        if (previous != null) {
            previous.generation++;
            deps.hud().hide(player);
        }
        long now = deps.tick().getAsLong();
        Active a = new Active(player, destination, now);
        a.snapshot = snapshot;
        a.goals = goals.goals();
        a.target = goals.target();
        a.region = goals.region();
        active.put(player.getUniqueId(), a);
        if (goals.direct()) {
            startDirect(a);
            return;
        }
        a.session = new NavigationSession(sessionParameters, new ManeuverBuilder(snapshot)::build, now);
        apply(a, a.session.start());
    }

    // ==================== goals (DESIGN §6.2, §6.3) ====================

    /** The goal set of a destination for the player's position, or why there is none. Main thread. */
    Goals resolveGoals(Player player, RoadNetworkSnapshot snapshot, Destination destination) {
        Location feet = player.getLocation();
        double px = feet.getX(), pFloorY = feet.getY() - 1, pz = feet.getZ();
        double maxSnap = routerParameters.maxSnapDistance();
        Snapper snapper = new Snapper(snapshot, routerParameters);
        switch (destination.kind()) {
            case POINT -> {
                double[] p = destination.point();
                double[] floor = {p[0], p[1] - 1, p[2]};
                if (distance(px, pFloorY, pz, floor) <= sessionParameters.arriveDistance()) {
                    return Goals.already();
                }
                if (distance(px, pFloorY, pz, floor) <= maxSnap) {
                    return new Goals(List.of(), floor, null, true, null, false);
                }
                Optional<SnapPoint> goal = snapper.snapFloor(floor[0], floor[1], floor[2]);
                if (goal.isEmpty()) {
                    return Goals.refused(NavigationMessages.destinationTooFar(destination.name()));
                }
                return new Goals(List.of(goal.get()), floor, null, false, null, false);
            }
            case NODE -> {
                Optional<SnapPoint> goal = snapshot.node(destination.nodeId()).map(n -> SnapPoint.atNode(snapshot, n.id()));
                if (goal.isEmpty()) {
                    return Goals.refused(NavigationMessages.noLocation(destination.name()));
                }
                double[] floor = goal.get().point();
                if (distance(px, pFloorY, pz, floor) <= sessionParameters.arriveDistance()) {
                    return Goals.already();
                }
                if (distance(px, pFloorY, pz, floor) <= maxSnap) {
                    return new Goals(List.of(), floor, null, true, null, false);
                }
                return new Goals(List.of(goal.get()), floor, null, false, null, false);
            }
            case REGION -> {
                Optional<RegionShape> shape = deps.regionShapes().shape(destination.world(), destination.regionId());
                if (shape.isEmpty()) {
                    return Goals.refused(NavigationMessages.noLocation(destination.name()));
                }
                RegionShape region = shape.get();
                // First, before any goal or mode decision (fix plan 5.5 item 4), with WorldGuard's answer.
                if (insideRegion(destination, region, feet)) {
                    return Goals.already();
                }
                return regionGoals(destination, region, snapshot, snapper, px, pFloorY, pz);
            }
            case STREET -> {
                List<SnapPoint> goals = streetPoints(snapshot, destination.streetId(), px, pFloorY, pz);
                SnapPoint nearest = nearest(goals, px, pFloorY, pz);
                if (nearest == null) {
                    return Goals.refused(NavigationMessages.noLocation(destination.name()));
                }
                double nearestDistance = distance(px, pFloorY, pz, nearest.point());
                if (nearestDistance <= sessionParameters.arriveDistance()) {
                    return Goals.already();
                }
                if (nearestDistance <= maxSnap) {
                    return new Goals(List.of(), nearest.point(), null, true, null, false);
                }
                return new Goals(goals, null, null, false, null, false);
            }
            default -> throw new IllegalStateException("unhandled " + destination.kind());
        }
    }

    /**
     * A region is reached along the roads, like a Location (fix plan 5.5 item 2, DESIGN §6.3): the
     * goals are where a road enters the region - multi-goal A* picks the one nearest <i>by road</i>
     * - or, when no road enters it, the road's nearest approach followed by a short straight last
     * leg (at most max-snap). A straight line to the region's edge (direct mode) only when no road
     * helps: the region is within {@link #REGION_DIRECT_DISTANCE} blocks, no nearer than the
     * nearest road, or no road comes within max-snap of it while the region does.
     */
    Goals regionGoals(Destination destination, RegionShape region, RoadNetworkSnapshot snapshot, Snapper snapper,
                      double px, double pFloorY, double pz) {
        double maxSnap = routerParameters.maxSnapDistance();
        List<SnapPoint> goals = RegionClosestPoint.crossings(region, snapshot);
        double[] lastLeg = null;
        if (goals.isEmpty()) {
            Optional<SnapPoint> approach = RegionClosestPoint.closest(region, snapshot);
            if (approach.isPresent()) {
                double[] p = approach.get().point();
                if (region.distanceFromFloor(p[0], p[1], p[2]) <= maxSnap) {
                    goals = List.of(approach.get());
                    lastLeg = region.closestPointFromFloor(p[0], p[1], p[2]);
                }
            }
        }
        double toRegion = region.distanceFromFloor(px, pFloorY, pz);
        Optional<SnapPoint> start = snapper.snapFloor(px, pFloorY, pz);
        boolean roadHelps = !goals.isEmpty() && start.isPresent()
            && toRegion > REGION_DIRECT_DISTANCE && toRegion > start.get().distance();
        if (roadHelps) {
            return new Goals(goals, lastLeg, region, false, null, false);
        }
        if (toRegion <= maxSnap) {
            return new Goals(List.of(), region.closestPointFromFloor(px, pFloorY, pz), region, true, null, false);
        }
        if (goals.isEmpty()) {
            return Goals.refused(NavigationMessages.destinationTooFar(destination.name()));
        }
        return new Goals(goals, lastLeg, region, false, null, false); // computeRoute: "too far from a road"
    }

    /** Whether the player's feet are inside the destination region (WorldGuard's answer on the server). */
    private boolean insideRegion(Destination destination, RegionShape region, Location feet) {
        return deps.regionShapes().containsFeet(destination.world(), destination.regionId(), region,
            feet.getX(), feet.getY(), feet.getZ());
    }

    private boolean insideRegion(Active a, Location feet) {
        return a.region != null && a.destination.kind() == Destination.Kind.REGION && insideRegion(a.destination, a.region, feet);
    }

    /** For every edge labelled {@code streetId}, its point nearest the floor position. */
    static List<SnapPoint> streetPoints(RoadNetworkSnapshot snapshot, int streetId, double x, double floorY, double z) {
        List<SnapPoint> points = new ArrayList<>();
        for (RoadEdge edge : snapshot.edges()) {
            if (edge.streetId().isPresent() && edge.streetId().getAsInt() == streetId) {
                points.add(nearestOnEdge(snapshot, edge, x, floorY, z));
            }
        }
        return points;
    }

    private static SnapPoint nearest(List<SnapPoint> points, double x, double floorY, double z) {
        SnapPoint nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (SnapPoint p : points) {
            double d = distance(x, floorY, z, p.point());
            if (d < nearestDistance) {
                nearestDistance = d;
                nearest = p;
            }
        }
        return nearest;
    }

    /** The point of {@code edge} nearest the player, sampled every {@link #STREET_SAMPLE_SPACING} blocks. */
    static SnapPoint nearestOnEdge(RoadNetworkSnapshot snapshot, RoadEdge edge, double x, double floorY, double z) {
        EdgePolyline polyline = snapshot.polyline(edge);
        double best = Double.POSITIVE_INFINITY;
        double bestAlong = 0;
        double total = polyline.length();
        for (double a = 0; ; a += STREET_SAMPLE_SPACING) {
            double at = Math.min(a, total);
            double[] p = polyline.pointAt(at);
            double d = distance(x, floorY, z, p);
            if (d < best) {
                best = d;
                bestAlong = at;
            }
            if (at >= total) {
                break;
            }
        }
        return SnapPoint.onEdge(snapshot, edge.id(), bestAlong);
    }

    // ==================== direct mode ====================

    private void startDirect(Active a) {
        DirectLeg leg = new DirectLeg(a.target);
        a.leg = leg;
        Location feet = a.player.getLocation();
        double d = distance(feet.getX(), feet.getY() - 1, feet.getZ(), leg.target);
        leg.total = Math.max(1, d);
        leg.best = d;
        a.player.sendMessage(NavigationMessages.startedDirect(a.destination.name(), leg.total));
        deps.trail().drawDirect(a.player, leg.target);
        deps.hud().update(a.player, a.destination.name(), leg.total, 0);
    }

    private void tickDirect(Active a, long now) {
        DirectLeg leg = a.leg;
        Location feet = a.player.getLocation();
        double d = distance(feet.getX(), feet.getY() - 1, feet.getZ(), leg.target);
        if (d <= sessionParameters.arriveDistance() || insideRegion(a, feet)) {
            arrive(a);
            return;
        }
        leg.best = Math.min(leg.best, d);
        if ((now - a.startedTick) % deps.trail().periodTicks() == 0) {
            deps.trail().drawDirect(a.player, leg.target);
        }
        if ((now - a.startedTick) % HUD_TICKS == 0) {
            deps.hud().update(a.player, a.destination.name(), d, 1 - Math.min(1, d / leg.total));
            deps.hud().arrowTowards(a.player, leg.target[0], leg.target[2]);
        }
    }

    /**
     * Direct mode's own periodic re-check (fix plan 5.5 item 3), on the routed re-check's cadence
     * ({@link #RECHECK_TICKS}; also for the last off-road leg after a road's end). A player who
     * walks away - more than {@code reroute-distance} farther from the target than their closest
     * approach - gets "heading away - recalculating", a target re-derived from where they stand (a
     * region's closest point, a street's nearest point; a Location stays put), a redrawn trail and a
     * reset HUD; at most once per {@code reroute-min-interval}. Direct mode is not promoted to a
     * routed session here (plan decision): {@code /navigate} again does that.
     */
    void recheckDirect(Active a, long now) {
        DirectLeg leg = a.leg;
        Location feet = a.player.getLocation();
        double x = feet.getX(), floorY = feet.getY() - 1, z = feet.getZ();
        double d = distance(x, floorY, z, leg.target);
        if (d <= leg.best + sessionParameters.rerouteDistance()) {
            return;
        }
        if (leg.lastRecalcTick != Long.MIN_VALUE && now - leg.lastRecalcTick < sessionParameters.rerouteMinIntervalTicks()) {
            return;
        }
        leg.lastRecalcTick = now;
        leg.target = directTarget(a, leg, x, floorY, z);
        double fresh = distance(x, floorY, z, leg.target);
        leg.total = Math.max(1, fresh);
        leg.best = fresh;
        a.player.sendMessage(NavigationMessages.directRecalculating(a.destination.name()));
        deps.events().accept(new NavigationRerouteEvent(a.player, a.destination.name(), RouteReason.OFF_ROUTE, "direct"));
        deps.trail().drawDirect(a.player, leg.target);
        deps.hud().update(a.player, a.destination.name(), fresh, 0);
        deps.hud().arrowTowards(a.player, leg.target[0], leg.target[2]);
    }

    /** The direct-mode target for a player at this floor position. */
    private double[] directTarget(Active a, DirectLeg leg, double x, double floorY, double z) {
        return switch (a.destination.kind()) {
            case REGION -> a.region == null ? leg.target : a.region.closestPointFromFloor(x, floorY, z);
            case STREET -> {
                SnapPoint nearest = nearest(streetPoints(a.snapshot, a.destination.streetId(), x, floorY, z), x, floorY, z);
                yield nearest == null ? leg.target : nearest.point();
            }
            default -> leg.target;
        };
    }

    // ==================== ticking ====================

    /** One server tick for every session (the ticker; tests call it directly). Main thread. */
    void tickAll() {
        if (active.isEmpty()) {
            return;
        }
        long now = deps.tick().getAsLong();
        for (Active a : new ArrayList<>(active.values())) {
            if (active.get(a.player.getUniqueId()) != a) {
                continue;
            }
            try {
                tick(a, now);
            } catch (RuntimeException e) {
                deps.logger().log(Level.WARNING, "[Navigation] Tick failed for " + a.player.getName() + "; ending their session", e);
                end(a, EndReason.NO_ROUTE);
            }
        }
    }

    private void tick(Active a, long now) {
        if (!a.player.isOnline()) {
            end(a, EndReason.QUIT);
            return;
        }
        if (!a.player.getWorld().getName().equalsIgnoreCase(a.destination.world())) {
            end(a, EndReason.WORLD_CHANGE);
            return;
        }
        if (now - a.lastRecheckTick >= RECHECK_TICKS) {
            a.lastRecheckTick = now;
            if (deps.eligibility().inSiege(a.player.getUniqueId())) {
                end(a, EndReason.SIEGE);
                return;
            }
            if (!a.direct() && now - a.startedTick >= sessionParameters.maxSessionTicks()) {
                end(a, EndReason.TIMEOUT);
                return;
            }
            if (a.direct()) {
                recheckDirect(a, now);
            } else {
                recheck(a, now);
            }
            if (active.get(a.player.getUniqueId()) != a) {
                return;
            }
        }
        if (a.direct()) {
            if (now - a.startedTick >= sessionParameters.maxSessionTicks()) {
                end(a, EndReason.TIMEOUT);
                return;
            }
            tickDirect(a, now);
            return;
        }
        Location feet = a.player.getLocation();
        if (insideRegion(a, feet)) {
            arrive(a);
            return;
        }
        apply(a, a.session.tick(feet.getX(), feet.getY(), feet.getZ(), now));
    }

    // ==================== effects ====================

    private void apply(Active a, List<NavigationEffect> effects) {
        for (NavigationEffect effect : effects) {
            if (active.get(a.player.getUniqueId()) != a) {
                return; // ended by an earlier effect
            }
            switch (effect) {
                case ComputeRouteEffect e -> computeRoute(a, e);
                case RouteAdoptedEffect e -> routeAdopted(a, e);
                case RerouteStartedEffect e -> rerouteStarted(a, e);
                case RouteKeptEffect e -> deps.logger().fine("[Navigation] " + a.player.getName() + ": route kept");
                case GuidanceEffect e -> guidance(a, e);
                case ArrivedEffect e -> arrivedAtRouteEnd(a);
                case EndedEffect e -> ended(a, e.reason());
            }
        }
    }

    private void computeRoute(Active a, ComputeRouteEffect effect) {
        Player player = a.player;
        Location feet = player.getLocation();
        RoadNetworkSnapshot snapshot = a.snapshot;
        Optional<SnapPoint> start = new Snapper(snapshot, routerParameters).snap(feet.getX(), feet.getY(), feet.getZ());
        if (start.isEmpty()) {
            if (effect.reason() == RouteReason.INITIAL) {
                player.sendMessage(NavigationMessages.playerTooFar(routerParameters.maxSnapDistance()));
                end(a, EndReason.NO_ROUTE, false);
                return;
            }
            deliver(a, a.generation, RouteResult.noRoute());
            return;
        }
        AccessPolicy policy = deps.policies().policyFor(player, snapshot);
        RouteRequest request = RouteRequest.of(start.get(), a.goals, policy, routerParameters);
        int generation = a.generation;
        deps.routing().execute(() -> {
            RouteResult result;
            try {
                result = new AStarRouter(snapshot).routeOrExplain(request);
            } catch (RuntimeException e) {
                deps.logger().log(Level.WARNING, "[Navigation] Routing failed for " + player.getName(), e);
                result = RouteResult.noRoute();
            }
            RouteResult delivered = result;
            deps.mainThread().execute(() -> deliver(a, generation, delivered));
        });
    }

    /** Main thread: a route result for a still-current session. */
    private void deliver(Active a, int generation, RouteResult result) {
        if (active.get(a.player.getUniqueId()) != a || a.generation != generation || a.session == null) {
            return;
        }
        apply(a, a.session.onRouteResult(result, deps.tick().getAsLong()));
    }

    private void routeAdopted(Active a, RouteAdoptedEffect effect) {
        Player player = a.player;
        Route route = effect.route();
        a.announcedManeuvers = 0;
        switch (effect.reason()) {
            case INITIAL -> {
                player.sendMessage(NavigationMessages.started(a.destination.name(), route.length(), eta));
                effect.explanation().ifPresent(why -> player.sendMessage(NavigationMessages.partialRoute(a.destination.name(), why)));
            }
            case IMPROVEMENT -> player.sendMessage(NavigationMessages.shorterRoute());
            case ELEMENT_BLOCKED, OFF_ROUTE -> effect.explanation()
                .ifPresent(why -> player.sendMessage(NavigationMessages.partialRoute(a.destination.name(), why)));
        }
        if (!a.hintShown) {
            for (Route.Step step : route.passThroughSteps()) {
                if (step.verdict() != null && step.verdict().message() != null) {
                    player.sendMessage(NavigationMessages.passThroughHint(step.verdict().message()));
                    a.hintShown = true;
                    break;
                }
            }
        }
        if (effect.reason() != RouteReason.INITIAL) {
            String detail = effect.explanation().map(BlockedExplainer.Explanation::reason).orElse(null);
            deps.events().accept(new NavigationRerouteEvent(player, a.destination.name(), effect.reason(), detail));
        }
        deps.trail().drawRoute(player, route, 0, a.target);
        deps.hud().update(player, a.destination.name(), route.length(), 0);
    }

    private void rerouteStarted(Active a, RerouteStartedEffect effect) {
        if (effect.reason() == RouteReason.OFF_ROUTE) {
            a.player.sendMessage(NavigationMessages.offRoute());
        } else {
            effect.verdict().ifPresent(v -> a.player.sendMessage(NavigationMessages.elementBlocked(v)));
        }
    }

    private void guidance(Active a, GuidanceEffect effect) {
        long since = deps.tick().getAsLong() - a.startedTick;
        Route route = a.route().orElse(null);
        if (route == null) {
            return;
        }
        String label = a.destination.name();
        if (effect.nextManeuver().isPresent() && effect.metersToNext() <= MANEUVER_ANNOUNCE_DISTANCE) {
            Maneuver next = effect.nextManeuver().get();
            label = next.text();
            int index = a.session.maneuvers().indexOf(next);
            if (index >= a.announcedManeuvers) {
                a.announcedManeuvers = index + 1;
                a.player.sendMessage(NavigationMessages.maneuver(effect.metersToNext(), next.text()));
            }
        }
        if (since % deps.trail().periodTicks() == 0) {
            deps.trail().drawRoute(a.player, route, effect.along(), a.target);
        }
        if (since % HUD_TICKS == 0) {
            deps.hud().update(a.player, label, effect.remainingBlocks(), effect.progress());
            deps.hud().arrowTowards(a.player, effect.aheadPoint()[0], effect.aheadPoint()[2]);
        }
    }

    /** The core session reached the road's end: the real arrival, or the last off-road leg to the target. */
    private void arrivedAtRouteEnd(Active a) {
        if (a.target != null) {
            Location feet = a.player.getLocation();
            double d = distance(feet.getX(), feet.getY() - 1, feet.getZ(), a.target);
            if (d > sessionParameters.arriveDistance() && d <= routerParameters.maxSnapDistance()) {
                a.session = null;
                startDirect(a);
                return;
            }
        }
        arrive(a);
    }

    private void arrive(Active a) {
        active.remove(a.player.getUniqueId());
        a.generation++;
        deps.hud().hide(a.player);
        a.player.sendMessage(NavigationMessages.arrived(a.destination.name()));
        a.player.playSound(ARRIVAL_SOUND);
        deps.events().accept(new NavigationArriveEvent(a.player, a.destination.name()));
    }

    private void ended(Active a, EndReason reason) {
        end(a, reason, true);
    }

    private void end(Active a, EndReason reason) {
        if (a.session != null) {
            a.session.end(reason);
        }
        end(a, reason, true);
    }

    private void end(Active a, EndReason reason, boolean message) {
        if (active.remove(a.player.getUniqueId()) != a) {
            return;
        }
        a.generation++;
        deps.hud().hide(a.player);
        if (message && a.player.isOnline()) {
            a.player.sendMessage(NavigationMessages.ended(reason, a.destination.name()));
        }
        deps.events().accept(new NavigationEndEvent(a.player, a.destination.name(), reason));
    }

    // ==================== live changes (DESIGN §6.7, D13) ====================

    /**
     * D13 safety net and the listeners' work: re-evaluate the active route's verdicts with a fresh
     * policy; the first blocked step → re-route with the reason; a partial route whose blocking
     * element is no longer blocked → try the full route again.
     */
    void recheck(Active a, long now) {
        Route route = a.route().orElse(null);
        if (route == null || a.session == null || a.session.state() != NavigationSession.State.GUIDING) {
            return;
        }
        AccessPolicy policy = deps.policies().policyFor(a.player, a.snapshot);
        for (Route.Step step : route.steps()) {
            EdgeVerdict verdict = policy.check(step.edge());
            if (verdict.isBlocked()) {
                apply(a, a.session.onElementBlocked(verdict, now));
                return;
            }
        }
        Optional<BlockedExplainer.Explanation> why = a.session.explanation();
        if (why.isPresent() && !policy.check(why.get().blockedEdge()).isBlocked()) {
            apply(a, a.session.onElementOpened(now));
        }
    }

    /** A gate changed state (R4 listener, hopped to the main thread by the caller). */
    public void onGateChanged(int doorId) {
        long now = deps.tick().getAsLong();
        for (Active a : new ArrayList<>(active.values())) {
            if (a.direct() || a.session == null) {
                continue;
            }
            boolean onRoute = a.route().map(r -> r.steps().stream().anyMatch(s -> s.edge().gateDoorIds().contains(doorId))).orElse(false)
                || a.session.explanation().map(e -> e.blockedEdge().gateDoorIds().contains(doorId)).orElse(false);
            if (onRoute) {
                a.lastRecheckTick = now;
                recheck(a, now);
            } else {
                apply(a, a.session.onElementOpened(now));
            }
        }
    }

    /** Something may have opened anywhere (domain cache refresh, siege lockdown change): every session re-checks. */
    public void onAvailabilityChanged() {
        long now = deps.tick().getAsLong();
        for (Active a : new ArrayList<>(active.values())) {
            if (a.direct() || a.session == null) {
                continue;
            }
            a.lastRecheckTick = now;
            recheck(a, now);
            if (active.get(a.player.getUniqueId()) == a && a.session.state() == NavigationSession.State.GUIDING) {
                apply(a, a.session.onElementOpened(now));
            }
        }
    }

    /** The world's network snapshot was swapped: goals are re-derived and a fresh route requested. */
    public void onNetworkChanged(String world) {
        RoadNetworkSnapshot snapshot = deps.snapshots().apply(world);
        long now = deps.tick().getAsLong();
        for (Active a : new ArrayList<>(active.values())) {
            if (!a.destination.world().equalsIgnoreCase(world) || a.direct() || a.session == null) {
                continue;
            }
            if (snapshot == null || snapshot.isEmpty()) {
                end(a, EndReason.DESTINATION_LOST);
                continue;
            }
            Goals goals = resolveGoals(a.player, snapshot, a.destination);
            if (goals.alreadyThere()) {
                arrive(a);
                continue;
            }
            if (goals.refusal() != null) {
                end(a, EndReason.DESTINATION_LOST);
                continue;
            }
            a.snapshot = snapshot;
            a.goals = goals.goals();
            a.target = goals.target();
            a.region = goals.region();
            if (goals.direct()) {
                a.session = null;
                startDirect(a);
                continue;
            }
            apply(a, a.session.onElementOpened(now));
        }
    }

    /** A teleport of more than {@link #TELEPORT_END_DISTANCE} blocks (or to another world) ends the session. */
    public void onTeleport(Player player, Location from, Location to) {
        Active a = active.get(player.getUniqueId());
        if (a == null || from == null || to == null) {
            return;
        }
        boolean otherWorld = from.getWorld() != to.getWorld();
        if (otherWorld || from.distance(to) > TELEPORT_END_DISTANCE) {
            end(a, EndReason.TELEPORT);
        }
    }

    // ==================== SiegeMatchObserver (R24) ====================

    @Override
    public void lobbyChanged(SiegeLobbyRuntime lobby) {
        for (Active a : new ArrayList<>(active.values())) {
            if (lobby.isMember(a.player.getUniqueId())) {
                end(a, EndReason.SIEGE);
            }
        }
    }

    @Override
    public void areaLockdownStarted(SiegeLobbyRuntime lobby, net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario scenario) {
        onAvailabilityChanged();
    }

    @Override
    public void objectiveCaptured(SiegeLobbyRuntime lobby, SiegeMatch match,
                                  net.knightsandkings.knk.core.siege.ObjectiveState.CaptureEvent capture) {
        onAvailabilityChanged();
    }

    @Override
    public void roundReleased(SiegeLobbyRuntime lobby) {
        onAvailabilityChanged();
    }

    @Override
    public void matchEnded(SiegeLobbyRuntime lobby, SiegeMatch match) {
        onAvailabilityChanged();
    }

    // ==================== /knk road why (DESIGN §7) ====================

    /**
     * The route {@code as} would get to {@code destination}, with every gate / domain verdict along
     * the way, for {@code /knk road why <destination> [--as <player>]}. Routing runs on the routing
     * thread; {@code out} receives the lines on the main thread.
     */
    public void explain(Player as, Destination destination, Consumer<Component> out) {
        String world = as.getWorld().getName();
        RoadNetworkSnapshot snapshot = deps.snapshots().apply(world);
        out.accept(NavigationMessages.whyHeader(as.getName(), destination.name()));
        if (snapshot == null || snapshot.isEmpty()) {
            out.accept(NavigationMessages.noNetworkHere(world));
            return;
        }
        if (!destination.world().equalsIgnoreCase(world)) {
            out.accept(NavigationMessages.otherWorld(destination.name()));
            return;
        }
        Goals goals = resolveGoals(as, snapshot, destination);
        if (goals.alreadyThere()) {
            out.accept(NavigationMessages.alreadyThere(destination.name()));
            return;
        }
        if (goals.refusal() != null) {
            out.accept(goals.refusal());
            return;
        }
        if (goals.direct()) {
            out.accept(NavigationMessages.whyResult(destination.kind() == Destination.Kind.REGION
                ? "Direct mode: the region's edge is a few steps away or nearer than any road, no road is used."
                : "Direct mode: the target is within " + (int) routerParameters.maxSnapDistance() + " blocks, no road is used.", true));
            return;
        }
        Location feet = as.getLocation();
        Optional<SnapPoint> start = new Snapper(snapshot, routerParameters).snap(feet.getX(), feet.getY(), feet.getZ());
        if (start.isEmpty()) {
            out.accept(NavigationMessages.playerTooFar(routerParameters.maxSnapDistance()));
            return;
        }
        AccessPolicy policy = deps.policies().policyFor(as, snapshot);
        RouteRequest request = RouteRequest.of(start.get(), goals.goals(), policy, routerParameters);
        deps.routing().execute(() -> {
            List<Component> lines = new ArrayList<>();
            try {
                RouteResult result = new AStarRouter(snapshot).routeOrExplain(request);
                switch (result.status()) {
                    case FOUND -> {
                        Route route = result.route();
                        lines.add(NavigationMessages.whyResult("Open route: " + EtaEstimator.formatDistance(route.length())
                            + ", " + route.steps().size() + " edges.", true));
                        for (Route.Step step : route.steps()) {
                            if (step.verdict() != null && !step.verdict().isOpen()) {
                                lines.add(NavigationMessages.whyVerdict(step.edge().id(), step.verdict()));
                            }
                        }
                    }
                    case BLOCKED -> {
                        BlockedExplainer.Explanation why = result.explanation();
                        lines.add(NavigationMessages.whyResult("No open route - " + why.reason() + ". Partial route: "
                            + EtaEstimator.formatDistance(why.partialRoute().length()) + ".", false));
                        Route full = why.fullRoute().withVerdicts(snapshot, policy);
                        for (Route.Step step : full.steps()) {
                            if (step.verdict() != null && !step.verdict().isOpen()) {
                                lines.add(NavigationMessages.whyVerdict(step.edge().id(), step.verdict()));
                            }
                        }
                    }
                    case DIFFERENT_COMPONENTS -> lines.add(NavigationMessages.noRoadConnects(destination.name()));
                    case NO_ROUTE -> lines.add(NavigationMessages.noRoute(destination.name()));
                }
            } catch (RuntimeException e) {
                lines.add(NavigationMessages.bad("Routing failed: " + e.getMessage()));
            }
            deps.mainThread().execute(() -> lines.forEach(out));
        });
    }

    // ==================== helpers ====================

    private double remaining(Active a) {
        if (a.direct()) {
            Location feet = a.player.getLocation();
            return distance(feet.getX(), feet.getY() - 1, feet.getZ(), a.leg.target);
        }
        return a.session == null ? 0 : a.session.remainingBlocks();
    }

    static double distance(double x, double y, double z, double[] p) {
        double dx = p[0] - x, dy = p[1] - y, dz = p[2] - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** For the tests. */
    Optional<NavigationSession> sessionOf(UUID playerId) {
        Active a = active.get(playerId);
        return a == null ? Optional.empty() : Optional.ofNullable(a.session);
    }

    boolean isDirect(UUID playerId) {
        Active a = active.get(playerId);
        return a != null && a.direct();
    }
}
