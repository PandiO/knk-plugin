package net.knightsandkings.knk.paper.scenario.scenarios;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.navigation.shape.PlazaCrossing;
import net.knightsandkings.knk.core.npc.nav.AccessRules;
import net.knightsandkings.knk.core.npc.nav.HopPlanner;
import net.knightsandkings.knk.core.npc.nav.LocalLeg;
import net.knightsandkings.knk.core.npc.nav.LocalLegPreparer;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcMoverFactory;
import net.knightsandkings.knk.core.npc.nav.NpcNavState;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner;
import net.knightsandkings.knk.core.npc.nav.group.Formation;
import net.knightsandkings.knk.core.npc.nav.group.GroupSettings;
import net.knightsandkings.knk.core.npc.nav.group.NavGroup;
import net.knightsandkings.knk.core.npc.nav.group.NavGroupService;
import net.knightsandkings.knk.core.roads.walk.CellAccess;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.knightsandkings.knk.paper.npc.citizens.CitizensBootstrap;
import net.knightsandkings.knk.paper.npc.nav.BukkitNavWorld;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;

/**
 * Real NPCs walking a scene for the NPC scenarios (implementation plan 4b.2 item 18): K&K's navigation service and
 * groups on Citizens' movers (their own NPC registry, beside the navigation module's), with a fake road: the scene's
 * route line, walked forward round its loop from the point nearest the NPC to the point nearest the goal. Local legs
 * get open cell access in a box round them (the scene has no regions or gates). Ticks the service every server tick
 * while it runs, keeps the run log with ticks, and watches how long each NPC stands in one block.
 *
 * <p>Needs Citizens (CI's {@code paper-boot} server has a pinned build); without it {@link #start} fails the step
 * with the reason.
 */
public final class ScenarioNpcs {

    /** A run-log line with the tick it was written in. */
    public record LogLine(long tick, String text) {
    }

    private final ScenarioScene scene;
    private final List<NavPoint> loop;
    /** Gate crossings on the scene's road: a plan whose line passes one names its door (4b.3 item 14). */
    private final List<RoadPlanner.GateCrossing> gates;
    private final NpcMoverFactory factory;
    private final NavigationSettings settings;
    private final NpcNavigationMetrics metrics = new NpcNavigationMetrics();
    private final NpcNavigationService service;
    private final NavGroupService groups;
    private final List<NpcMover> movers = new ArrayList<>();
    private final List<NavGroup> groupList = new ArrayList<>();
    private final List<LogLine> log = new ArrayList<>();
    private final Map<Integer, int[]> lastBlock = new HashMap<>();
    private final Map<Integer, Integer> stillTicks = new HashMap<>();
    private long tick;
    /** K&K's navigation time on the main thread (group and service ticks) and NPC ticks, for the cost per NPC. */
    private long navNanos;
    private long npcTicks;
    private int longestStill;
    private String longestStillWho = "-";
    private BukkitTask task;

    private ScenarioNpcs(ScenarioScene scene, List<NavPoint> loop, NpcMoverFactory factory, NavigationSettings settings,
                         AccessRules rules, List<RoadPlanner.GateCrossing> gates) {
        this.scene = scene;
        this.loop = HopPlanner.resampleKeepingCorners(loop, 1.0);
        this.gates = List.copyOf(gates);
        this.factory = factory;
        this.settings = settings;
        metrics.setEnabled(true);
        // the scenarios with rules of their own see WorldGuard's regions (a scenario region) the way the live NPC module
        // sees them (PandiO/knk-workspace#668: a region query of their own hid that the module's was missing); the
        // others none, as in L3
        BukkitNavWorld world = rules == AccessRules.OPEN ? new BukkitNavWorld(null) : scene.services().npcRules();
        Consumer<String> logLine = line -> log.add(new LogLine(tick, line));
        service = new NpcNavigationService(withWalks(scene, new NpcNavigationService.Ports(this::plan, this::prepare,
            world, metrics, System::nanoTime, logLine, rules)), 4);
        groups = new NavGroupService(new NavGroupService.Ports(service, world, rules, metrics,
            System::nanoTime, logLine));
        // bodies with collision on (link M7), as the plugin wires them
        collision = new net.knightsandkings.knk.paper.npc.nav.BukkitCollisionPolicy(CitizensBootstrap::entity)
            .start(scene.services().plugin());
        service.setCollision(collision, new net.knightsandkings.knk.paper.npc.nav.BukkitNpcSight());
    }

    private final net.knightsandkings.knk.paper.npc.nav.BukkitCollisionPolicy collision;

    /**
     * Run the NPC scenarios with off-road legs in hops, as before link C1 (a system property for the before/after
     * runs of PandiO/knk-workspace#700: {@code -Dknk.scenario.offRoadHops=true}); off by default.
     */
    public static final String OFF_ROAD_HOPS = "knk.scenario.offRoadHops";

    /**
     * The ports with the walk navigator's search for off-road legs (link C1), as the plugin wires them, with every cell
     * open like the scenarios' local legs; without it when {@link #OFF_ROAD_HOPS} is set.
     */
    static NpcNavigationService.Ports withWalks(ScenarioScene scene, NpcNavigationService.Ports ports) {
        if (Boolean.getBoolean(OFF_ROAD_HOPS)) {
            return ports;
        }
        return ports.withWalks(new net.knightsandkings.knk.paper.npc.nav.NpcWalkPlanner(scene.services().npcWalks(),
            net.knightsandkings.knk.paper.npc.nav.NpcWalkPlanner.AccessFor.OPEN, scene.services().plugin().getLogger()));
    }

    /**
     * Starts NPC navigation on Citizens for a scene: fails the step when Citizens is not installed or not usable.
     *
     * @param loop     the scene's route line, floor points, once round its loop
     * @param settings the per-NPC settings (code defaults with the scenario's own values)
     */
    public static ScenarioNpcs start(ScenarioScene scene, List<NavPoint> loop, NavigationSettings settings) {
        return start(scene, loop, settings, AccessRules.OPEN, List.of());
    }

    /**
     * As {@link #start(ScenarioScene, List, NavigationSettings)} with the scenario's own access rules (fake access
     * data, a scenario gate's real state) and the gate crossings on its road (implementation plan 4b.3 item 14).
     * After a rule changes, call {@link #rulesChanged()} (the gate or availability event).
     */
    public static ScenarioNpcs start(ScenarioScene scene, List<NavPoint> loop, NavigationSettings settings,
                                     AccessRules rules, List<RoadPlanner.GateCrossing> gates) {
        CitizensBootstrap.unavailableReason().ifPresent(reason -> {
            throw new ScenarioAssertionError("NPC scenarios need Citizens: " + reason);
        });
        NpcMoverFactory factory = CitizensBootstrap.start(scene.services().plugin(),
            scene.uniqueName("npcs").replace('_', '-')).orElseThrow(
                () -> new ScenarioAssertionError("Citizens did not start for the scenario"));
        ScenarioNpcs npcs = new ScenarioNpcs(scene, loop, factory, settings, rules, gates);
        npcs.task = Bukkit.getScheduler().runTaskTimer(scene.services().plugin(), npcs::tick, 1L, 1L);
        return npcs;
    }

    /** Stops the ticking, cancels every order and removes the NPCs. Safe to call twice. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        collision.stop();
        for (NavGroup group : groupList) {
            groups.disband(group);
        }
        for (NpcMover mover : movers) {
            service.unregister(mover.id());
            mover.remove();
        }
        movers.clear();
        CitizensBootstrap.stop(factory);
    }

    private NpcMover traced;
    /** The junctions on the scene's road that may be plazas, handed out with every road plan (link M5). */
    private List<PlazaCrossing> plazas = List.of();
    private final List<Runnable> watchers = new ArrayList<>();

    /** The junctions on the scene's road that may be plazas (link M5): each road plan names them, as the router does. */
    public void plazas(List<PlazaCrossing> crossings) {
        plazas = List.copyOf(crossings);
    }
    private final Map<NavGroup, List<NpcMover>> groupMembers = new HashMap<>();

    /** Runs {@code watcher} every tick after the NPCs moved (a scenario's own per-tick check). */
    public void everyTick(Runnable watcher) {
        watchers.add(watcher);
    }

    /** Writes an NPC's position and state to the run log every 5 ticks (for failure reasons). */
    public void trace(NpcMover mover) {
        traced = mover;
    }

    private void tick() {
        tick++;
        long started = System.nanoTime();
        groups.tick();
        service.tick();
        navNanos += System.nanoTime() - started;
        npcTicks += movers.size();
        if (traced != null && tick % 5 == 0) {
            NpcMover t = traced;
            log.add(new LogLine(tick, "trace " + t.name() + " " + t.position().map(p -> String.format(Locale.ROOT,
                "%.2f %.2f %.2f", p.x(), p.y(), p.z())).orElse("-") + " " + service.state(t.id()) + " moving "
                + t.isMoving()));
        }
        for (NpcMover mover : movers) {
            watchStill(mover);
        }
        for (Runnable watcher : watchers) {
            watcher.run();
        }
    }

    /** Removes single NPCs (a scenario that runs singles, then a group, PandiO/knk-workspace#621). */
    public void remove(List<NpcMover> singles) {
        for (NpcMover mover : List.copyOf(singles)) {
            service.unregister(mover.id());
            mover.remove();
            movers.remove(mover);
            stillTicks.remove(mover.id());
            lastBlock.remove(mover.id());
        }
    }

    /** Ends a group and removes its NPCs (a scenario that runs two groups one after the other). */
    public void dismiss(NavGroup group) {
        groups.disband(group);
        groupList.remove(group);
        for (NpcMover mover : groupMembers.getOrDefault(group, List.of())) {
            service.unregister(mover.id());
            mover.remove();
            movers.remove(mover);
        }
        groupMembers.remove(group);
    }

    private void watchStill(NpcMover mover) {
        NavPoint at = mover.position().orElse(null);
        NpcNavState state = service.state(mover.id());
        boolean leaderWaits = false;
        boolean follower = false;
        for (NavGroup group : groupList) {
            leaderWaits |= group.leader().id() == mover.id() && group.holding();
            follower |= group.followerState(mover.id()) != null;
        }
        // an NPC crowd awareness holds (link M4) waits in a queue: not "still" (the plan's "outside a queue")
        if (at == null || follower || leaderWaits || state == null || state == NpcNavState.IDLE
            || service.crowdHeld(mover.id())) {
            stillTicks.put(mover.id(), 0);
            return;
        }
        int[] block = {at.blockX(), at.blockY(), at.blockZ()};
        int[] last = lastBlock.put(mover.id(), block);
        int still = last != null && java.util.Arrays.equals(last, block) ? stillTicks.getOrDefault(mover.id(), 0) + 1 : 0;
        stillTicks.put(mover.id(), still);
        if (still > longestStill) {
            longestStill = still;
            longestStillWho = String.format(Locale.ROOT, "%s at %d %d %d", mover.name(), block[0], block[1], block[2]);
        }
    }

    // ==================== the fake road and local legs ====================

    private int nearest(NavPoint floor) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < loop.size(); i++) {
            NavPoint p = loop.get(i);
            double d = sq(p.x() - floor.x()) + sq(p.y() - floor.y()) + sq(p.z() - floor.z());
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private CompletableFuture<RoadPlanner.RoadPlan> plan(net.knightsandkings.knk.core.npc.nav.AccessSubject subject,
                                                         NavPoint from, NavPoint to) {
        int a = nearest(from);
        int b = nearest(to);
        List<NavPoint> line = new ArrayList<>();
        int n = loop.size() - 1; // the last point is the first again
        for (int i = a; line.size() <= loop.size(); i = (i + 1) % n) {
            line.add(loop.get(i));
            if (i == b) {
                break;
            }
        }
        if (line.size() < 2) {
            line.add(loop.get((a + 1) % n));
        }
        List<RoadPlanner.GateCrossing> crossed = new ArrayList<>();
        for (RoadPlanner.GateCrossing gate : gates) {
            for (NavPoint p : line) {
                if (p.horizontalDistance(gate.at()) <= 1.0 && Math.abs(p.y() - gate.at().y()) <= 2) {
                    crossed.add(gate);
                    break;
                }
            }
        }
        return CompletableFuture.completedFuture(RoadPlanner.RoadPlan.found(line, crossed).withPlazas(plazas));
    }

    private CompletableFuture<LocalLegPreparer.Prepared> prepare(net.knightsandkings.knk.core.npc.nav.AccessSubject subject,
                                                                 NavPoint feet, NavPoint target) {
        int minX = (int) Math.floor(Math.min(feet.x(), target.x())) - 16;
        int maxX = (int) Math.floor(Math.max(feet.x(), target.x())) + 16;
        int minZ = (int) Math.floor(Math.min(feet.z(), target.z())) - 16;
        int maxZ = (int) Math.floor(Math.max(feet.z(), target.z())) + 16;
        int minY = (int) Math.floor(Math.min(feet.y(), target.y())) - 8;
        int maxY = (int) Math.floor(Math.max(feet.y(), target.y())) + 12;
        return CompletableFuture.completedFuture(LocalLegPreparer.Prepared.ready(
            new LocalLeg(CellAccess.OPEN, minX, minY, minZ, maxX, maxY, maxZ, false)));
    }

    // ==================== NPCs ====================

    /** Spawns an NPC (feet position) under navigation with the scenario's settings. */
    public NpcMover spawn(NavPoint feet) {
        NavigationSettings.Scope scope = NavigationSettings.Scope.of(feet.world());
        NpcMover mover = factory.spawn("knk-sc-" + scene.fakeId(movers.size()) * -1 % 100000, feet,
            new NpcMoverFactory.MoverOptions(settings.value(NavigationSettings.PATHFINDER, scope),
                settings.value(NavigationSettings.STATIONARY_TICKS, scope),
                settings.value(NavigationSettings.COLLIDABLE, scope)));
        service.register(mover, NpcAccessProfile.DEFAULT, NpcNavigationService.Settings.of(settings, scope));
        movers.add(mover);
        return mover;
    }

    /** Sends an NPC round the loop for good: to {@code first}, then to {@code second}, then to {@code first} again. */
    public void patrol(NpcMover mover, NavPoint first, NavPoint second) {
        service.navigate(mover.id(), first, result -> {
            if (result instanceof NavigationResult.Arrived) {
                patrol(mover, second, first);
            } else if (!(result instanceof NavigationResult.Blocked)) {
                log.add(new LogLine(tick, mover.name() + " order ended: " + result));
            }
        });
    }

    /** A group of the movers (the first leads) in {@code formation}, sent round the loop for good. */
    public NavGroup patrolGroup(List<NpcMover> members, Formation.Type formation, NavPoint first, NavPoint second) {
        return patrolGroup(members, groupSettings(members.get(0), formation), first, second);
    }

    /** The scenario's group settings for a group led by {@code leader}, in {@code formation}. */
    public GroupSettings groupSettings(NpcMover leader, Formation.Type formation) {
        NavigationSettings.Scope scope = NavigationSettings.Scope.of(leader.position().orElseThrow().world());
        GroupSettings base = GroupSettings.of(settings, scope);
        return base.withFormation(base.formation().with(formation));
    }

    /** A group of the movers (the first leads) with its own settings, sent round the loop for good. */
    public NavGroup patrolGroup(List<NpcMover> members, GroupSettings own, NavPoint first, NavPoint second) {
        NavGroup group = groups.create(members.get(0), members.subList(1, members.size()), NpcAccessProfile.DEFAULT, own);
        groupList.add(group);
        groupMembers.put(group, List.copyOf(members));
        patrolGroup(group, first, second);
        return group;
    }

    /**
     * A group of the movers (the first leads) in {@code formation} with no order yet: the scenario sends it with
     * {@link NavGroup#navigate} (one trip, PandiO/knk-workspace#592).
     */
    public NavGroup group(List<NpcMover> members, Formation.Type formation) {
        NavGroup group = groups.create(members.get(0), members.subList(1, members.size()), NpcAccessProfile.DEFAULT,
            groupSettings(members.get(0), formation));
        groupList.add(group);
        groupMembers.put(group, List.copyOf(members));
        return group;
    }

    private void patrolGroup(NavGroup group, NavPoint first, NavPoint second) {
        group.navigate(first, result -> {
            if (result instanceof NavigationResult.Arrived) {
                patrolGroup(group, second, first);
            } else if (!(result instanceof NavigationResult.Blocked)) {
                log.add(new LogLine(tick, "group " + group.id() + " order ended: " + result));
            }
        });
    }

    /** A gate or an access rule changed: the events the plugin sends the navigation and group services. */
    public void rulesChanged() {
        service.onAvailabilityChanged();
        groups.onRulesChanged();
    }

    /** A gate door changed state: its event to the navigation and group services. */
    public void gateChanged(int doorId) {
        service.onGateChanged(doorId);
        groups.onRulesChanged();
    }

    /** Moves an NPC's body somewhere else at once (a player pushing it into a corner). */
    public void push(NpcMover mover, NavPoint feet) {
        if (!CitizensBootstrap.teleport(mover, feet)) {
            throw new ScenarioAssertionError(mover.name() + " is not a Citizens NPC");
        }
    }

    // ==================== what the checks read ====================

    public NpcNavigationMetrics metrics() {
        return metrics;
    }

    public NpcNavigationService service() {
        return service;
    }

    public List<LogLine> log() {
        return log;
    }

    /** A mover's name by its id ("#id" when it is gone). */
    public String nameOf(int id) {
        for (NpcMover m : movers) {
            if (m.id() == id) {
                return m.name();
            }
        }
        return "#" + id;
    }

    public List<NpcMover> movers() {
        return movers;
    }

    /** The most ticks an NPC with an order (a single NPC or a leader that does not wait for its group) stood in one block. */
    public int longestStillTicks() {
        return longestStill;
    }

    public String longestStillWho() {
        return longestStillWho;
    }

    /** Starts the longest-still watch afresh (a second phase of a scenario). */
    public void resetLongestStill() {
        longestStill = 0;
        longestStillWho = "-";
    }

    /**
     * K&K's navigation time on the main thread per NPC per tick so far, in milliseconds (the group and navigation
     * services' ticks; the budget of plan 4c.1 item 7 is 0.03 ms). NaN before the first NPC tick.
     */
    public double navMsPerNpcTick() {
        return npcTicks == 0 ? Double.NaN : navNanos / 1e6 / npcTicks;
    }

    /**
     * Corridor steering's share of {@link #navMsPerNpcTick} (plan 4c.3 decision 1.5, link M2): the time spent shaping
     * stretches per NPC per tick so far, in milliseconds. NaN before the first NPC tick.
     */
    public double corridorMsPerNpcTick() {
        return npcTicks == 0 ? Double.NaN : service.corridors().nanos() / 1e6 / npcTicks;
    }

    /** Crowd awareness's share of {@link #navMsPerNpcTick} (link M4), in milliseconds; NaN before the first NPC tick. */
    public double crowdMsPerNpcTick() {
        return npcTicks == 0 ? Double.NaN : service.crowdNanos() / 1e6 / npcTicks;
    }

    /** NPC ticks so far (one per NPC per tick), for a phase's cost per NPC per tick (link M5). */
    public long npcTicks() {
        return npcTicks;
    }

    /** The server ticks the NPCs have been ticked so far. */
    public long ticks() {
        return tick;
    }

    /** Null when the counters of 4b.2 item 12 add up, else why not. */
    public String countersAddUp() {
        long spells = metrics.total(Event.STUCK_ENGINE) + metrics.total(Event.STUCK_NO_PROGRESS);
        long ended = metrics.total(Event.RECOVERED) + metrics.total(Event.NOT_FREED) + metrics.spells().open();
        long freed = metrics.total(Event.FREED_BACK_OFF) + metrics.total(Event.FREED_SIDE_STEP)
            + metrics.total(Event.FREED_LOCAL_LEG) + metrics.total(Event.FREED_REPLAN);
        if (spells != ended) {
            return "stuck spells " + spells + " but freed + not freed + open " + ended;
        }
        return freed == metrics.total(Event.RECOVERED) ? null
            : "recovered " + metrics.total(Event.RECOVERED) + " is not the sum of the freed_* events " + freed;
    }

    /** The stats line of the local legs (link M6), with K&K's search counters, for failure reasons. */
    public String localLegs() {
        var legs = service.localLegs();
        return NpcNavigationMetrics.localLegsLine(metrics.total(Event.LOCAL_LEG_KNK), metrics.total(Event.LOCAL_LEG_ENGINE),
            metrics.total(Event.LOCAL_LEG_NONE)) + String.format(java.util.Locale.ROOT,
            " (K&K searches %d, found %d, %d cells, %.3f ms)", legs.searches(), legs.found(), legs.expansions(),
            legs.nanos() / 1e6);
    }

    /** The stats line of the spells, for failure reasons. */
    public String stats() {
        return metrics.spells().describe(metrics.total(Event.RECOVERY_NO_PATH), metrics.total(Event.FELL))
            + " | arrived " + metrics.total(Event.ARRIVED) + " | longest still (tracked) " + longestStill + " ticks, "
            + longestStillWho;
    }

    /** The last {@code n} run-log lines, for failure reasons. */
    public String tail(int n) {
        StringBuilder out = new StringBuilder();
        List<LogLine> events = log.stream().filter(line -> !line.text().startsWith("trace ")).toList();
        for (LogLine line : events.subList(Math.max(0, events.size() - n), events.size())) {
            out.append("\n  ").append(line.tick()).append(": ").append(line.text());
        }
        return out.toString();
    }

    private static double sq(double v) {
        return v * v;
    }
}
