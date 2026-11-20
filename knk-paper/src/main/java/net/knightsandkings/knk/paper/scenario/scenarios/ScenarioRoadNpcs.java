package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.core.domain.roads.RoadClass;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeSource;
import net.knightsandkings.knk.core.domain.roads.RoadNode;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.AccessRules;
import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcMoverFactory;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner;
import net.knightsandkings.knk.core.npc.nav.group.GroupSettings;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup;
import net.knightsandkings.knk.core.npc.nav.group.NavGroupService;
import net.knightsandkings.knk.core.roads.route.AccessPolicy;
import net.knightsandkings.knk.core.roads.route.EtaEstimator;
import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.knightsandkings.knk.paper.config.NavigationConfig;
import net.knightsandkings.knk.paper.navigation.NavigationEligibility;
import net.knightsandkings.knk.paper.navigation.NavigationHud;
import net.knightsandkings.knk.paper.navigation.NavigationService;
import net.knightsandkings.knk.paper.navigation.TrailRenderer;
import net.knightsandkings.knk.paper.navigation.WorldGuardRegionShapes;
import net.knightsandkings.knk.paper.navigation.walk.WalkLegPreparer;
import net.knightsandkings.knk.paper.npc.nav.BukkitNavWorld;
import net.knightsandkings.knk.paper.npc.nav.NpcLegPreparer;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import net.knightsandkings.knk.paper.utils.TickBudget;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * NPC navigation on a fake road network, for scenarios (PandiO/knk-workspace#543; "Fake data instead of the API" in
 * knk-workspace {@code docs/guides/developer/plugin-scenarios.md} § 4).
 * <p>
 * The road is one edge of a {@link RoadNetworkSnapshot} in scene coordinates, the graph the plugin's router reads. It
 * reaches the NPCs through the navigator's own adapter: {@link NavigationService#planRoadFor} on a road navigation
 * service built here whose network is that snapshot (snapping, components, the A* router and the route line as the
 * live one does), with every road open. Everything else is the live plugin's: the NPCs are spawned and moved by the
 * running Citizens adapter ({@code npc.navigation.enabled}), local legs are prepared by the live walk capture, and the
 * state machine ({@link NpcNavigationService}) and the groups ({@link NavGroupService}) are the plugin's classes,
 * built here with the code defaults of every per-NPC setting so a test server's {@code config.yml} doesn't change
 * the result, and ticked every server tick like the live ones. Nothing in the live plugin is swapped.
 * <p>
 * Build it in an {@code act} step and register {@link #close} as a cleanup: the NPCs are player entities, which the
 * scene does not remove.
 */
public final class ScenarioRoadNpcs {

    /** The scene y of the floor; NPCs stand at {@link #FEET_Y}. */
    public static final int FLOOR_Y = 1;
    public static final int FEET_Y = FLOOR_Y + 1;
    /** The block the road is drawn in (for a person watching; the route comes from the road graph). */
    public static final Material ROAD = Material.DIRT_PATH;
    /** The ground beside the road. */
    public static final Material GROUND = Material.GRASS_BLOCK;

    private final ScenarioScene scene;
    private final NpcMoverFactory factory;
    private final RoadNetworkSnapshot network;
    private final List<double[]> roadLine;
    private final NpcNavigationMetrics metrics = new NpcNavigationMetrics();
    private final NpcNavigationService service;
    private final NavGroupService groups;
    private final List<NpcMover> movers = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private BukkitTask ticker;
    private int nextName;

    /**
     * Lays the ground and the road, and builds the navigation on that road.
     *
     * @param road the road's centre line as scene (x, z) points, in order (at least two); 3 blocks wide
     */
    public ScenarioRoadNpcs(ScenarioScene scene, int[]... road) {
        if (road.length < 2) {
            throw new IllegalArgumentException("a road needs two points");
        }
        this.scene = scene;
        this.factory = scene.services().npcMovers();
        NavigationService live = scene.services().roadNavigation();
        NavigationService.Walk walk = live.walk();
        if (walk == null || !(walk.preparer() instanceof WalkLegPreparer legs)) {
            throw new ScenarioAssertionError("walk paths are off (navigation.walk.enabled): local legs need their capture");
        }
        ScenarioGates.floor(scene, 0, 0, ScenarioScene.SIZE - 1, ScenarioScene.SIZE - 1, FLOOR_Y, GROUND);
        this.roadLine = new ArrayList<>();
        for (int[] point : road) {
            roadLine.add(new double[] {scene.worldX(point[0]) + 0.5, scene.worldZ(point[1]) + 0.5});
        }
        drawRoad(road);
        this.network = network(scene, road);

        Plugin plugin = scene.services().plugin();
        // the router's answer always comes on a later tick, as the live one's does (the routing thread hands it back)
        Executor nextTick = task -> Bukkit.getScheduler().runTask(plugin, task);
        NavigationService router = router(scene, network);
        RoadPlanner roads = router::planRoadFor;
        metrics.setEnabled(true);
        BukkitNavWorld world = new BukkitNavWorld(null);
        this.service = new NpcNavigationService(ScenarioNpcs.withWalks(scene, new NpcNavigationService.Ports(roads,
            new NpcLegPreparer(legs, walk.searches(), nextTick,
                Bukkit::getWorld, plugin.getLogger()),
            world, metrics, System::nanoTime, log::add, AccessRules.OPEN)), 4);
        this.groups = new NavGroupService(new NavGroupService.Ports(service, world, AccessRules.OPEN, metrics,
            System::nanoTime, log::add));
    }

    /** Starts the per-tick work (groups first, then the navigation service, as the live module does). */
    public ScenarioRoadNpcs start() {
        ticker = Bukkit.getScheduler().runTaskTimer(scene.services().plugin(), () -> {
            groups.tick();
            service.tick();
        }, 1L, 1L);
        return this;
    }

    /** Spawns an NPC standing at a scene position (feet) and puts it under navigation with the default profile. */
    public NpcMover spawn(double x, double z) {
        NpcMover mover = factory.spawn("knk-scn-" + nextName++, feet(x, z),
            new NpcMoverFactory.MoverOptions("citizens", 60, false));
        movers.add(mover);
        service.register(mover, NpcAccessProfile.DEFAULT, NpcNavigationService.Settings.defaults());
        return mover;
    }

    /** Sends one NPC to a scene position; the result lands in the returned reference. */
    public AtomicReference<NavigationResult> navigate(NpcMover mover, double x, double z) {
        AtomicReference<NavigationResult> result = new AtomicReference<>();
        if (!service.navigate(mover.id(), feet(x, z), result::set)) {
            throw new ScenarioAssertionError("the navigation service refused the order for " + mover.name());
        }
        return result;
    }

    /** Makes a group (code-default settings with {@code settings} applied) of NPCs spawned with {@link #spawn}. */
    public NavGroup group(NpcMover leader, List<NpcMover> followers, GroupSettings settings) {
        return groups.create(leader, followers, NpcAccessProfile.DEFAULT, settings);
    }

    /** Feet position in the world for a scene position. */
    public NavPoint feet(double x, double z) {
        return new NavPoint(scene.world().getName(), scene.worldX(0) + x, scene.worldY(FEET_Y), scene.worldZ(0) + z);
    }

    /** Horizontal distance from a world position to the road's centre line, in blocks. */
    public double distanceFromRoad(NavPoint at) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < roadLine.size(); i++) {
            double[] a = roadLine.get(i);
            double[] b = roadLine.get(i + 1);
            double dx = b[0] - a[0];
            double dz = b[1] - a[1];
            double lengthSquared = dx * dx + dz * dz;
            double t = lengthSquared == 0 ? 0
                : Math.max(0, Math.min(1, ((at.x() - a[0]) * dx + (at.z() - a[1]) * dz) / lengthSquared));
            best = Math.min(best, Math.hypot(at.x() - (a[0] + t * dx), at.z() - (a[1] + t * dz)));
        }
        return best;
    }

    /** A scene-relative "x,z" for messages. */
    public String describe(NavPoint at) {
        return String.format(java.util.Locale.ROOT, "%.1f,%.1f (scene)", at.x() - scene.worldX(0), at.z() - scene.worldZ(0));
    }

    /** How often the navigation counted an event (a stretch handed over, a stuck NPC, ...). */
    public long count(NpcNavigationMetrics.Event event) {
        return metrics.total(event);
    }

    /** The navigation's log lines (stuck events, group folds and own legs), for a failure message. */
    public List<String> log() {
        return log;
    }

    /** Stops the ticks and removes every NPC this helper spawned. */
    public void close() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
        for (NpcMover mover : movers) {
            service.unregister(mover.id());
            mover.remove();
        }
        movers.clear();
    }

    private void drawRoad(int[][] road) {
        for (int i = 0; i + 1 < road.length; i++) {
            int x0 = road[i][0], z0 = road[i][1], x1 = road[i + 1][0], z1 = road[i + 1][1];
            if (x0 != x1 && z0 != z1) {
                throw new IllegalArgumentException("road segments run along x or z");
            }
            ScenarioGates.floor(scene, x0 - 1, z0 - 1, x1 + 1, z1 + 1, FLOOR_Y, ROAD);
        }
    }

    /**
     * The fake road network: one detected Main-class edge along {@code road} (floor points, the road graph's
     * convention), with an endpoint node at each end.
     */
    static RoadNetworkSnapshot network(ScenarioScene scene, int[][] road) {
        String world = scene.world().getName();
        int y = scene.worldY(FLOOR_Y);
        List<int[]> geometry = new ArrayList<>();
        double length = 0;
        for (int i = 0; i < road.length; i++) {
            geometry.add(new int[] {scene.worldX(road[i][0]), y, scene.worldZ(road[i][1])});
            if (i > 0) {
                length += Math.hypot(road[i][0] - road[i - 1][0], road[i][1] - road[i - 1][1]);
            }
        }
        int[] first = geometry.get(0);
        int[] last = geometry.get(geometry.size() - 1);
        RoadNetworkSnapshot.Builder builder = RoadNetworkSnapshot.builder(world);
        builder.addProfile(new RoadNetworkSnapshot.Profile(1, "Scenario road", RoadClass.MAIN, 1.0));
        builder.addNode(new RoadNode(1, first[0], first[1], first[2], RoadNodeKind.ENDPOINT, null, 1));
        builder.addNode(new RoadNode(2, last[0], last[1], last[2], RoadNodeKind.ENDPOINT, null, 1));
        builder.addEdge(new RoadEdge(1, 1, 2, geometry, length, 3, OptionalInt.of(1), OptionalInt.empty(), 1.0,
            EnumSet.noneOf(RoadEdgeFlag.class), List.of(), List.of(), List.of(), RoadEdgeSource.DETECTED, false));
        return builder.build();
    }

    /**
     * A road navigation service on {@code network} with the code defaults and every road open, built as the live one
     * is: its {@link NavigationService#planRoadFor} snaps, handles components and routes as the plugin's (link G1 also
     * routes a snapshot's road graph with it). Answers on the next tick.
     */
    static NavigationService router(ScenarioScene scene, RoadNetworkSnapshot network) {
        Plugin plugin = scene.services().plugin();
        Executor nextTick = task -> Bukkit.getScheduler().runTask(plugin, task);
        return new NavigationService(new NavigationService.Deps(plugin, NavigationConfig.defaults(),
            world -> world.equals(scene.world().getName()) ? network : RoadNetworkSnapshot.empty(world), OPEN_ROADS,
            new WorldGuardRegionShapes(), new NavigationEligibility(id -> false, id -> false, id -> false),
            new NavigationHud(new EtaEstimator(NavigationConfig.defaults().sessionParameters().sprintSpeed())),
            new TrailRenderer(NavigationConfig.defaults().trail(), TickBudget.server()), nextTick,
            // a scene's network is small: routed in place, answered on the next tick
            Runnable::run, () -> (long) Bukkit.getCurrentTick(), event -> { }, plugin.getLogger(), null));
    }

    /** Every road open, for players and NPCs alike: the scenarios test movement, not access rules. */
    private static final NavigationService.PolicyFactory OPEN_ROADS = new NavigationService.PolicyFactory() {
        @Override
        public AccessPolicy policyFor(Player player, RoadNetworkSnapshot snapshot) {
            return AccessPolicy.ALL_OPEN;
        }

        @Override
        public AccessPolicy policyFor(AccessSubject subject, RoadNetworkSnapshot snapshot,
                                      Set<String> destinationRegions) {
            return AccessPolicy.ALL_OPEN;
        }
    };
}
