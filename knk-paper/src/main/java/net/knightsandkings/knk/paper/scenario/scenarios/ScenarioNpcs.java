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

import net.knightsandkings.knk.core.npc.nav.AccessRules;
import net.knightsandkings.knk.core.npc.nav.HopPlanner;
import net.knightsandkings.knk.core.npc.nav.LocalLeg;
import net.knightsandkings.knk.core.npc.nav.LocalLegPreparer;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
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
    private int longestStill;
    private String longestStillWho = "-";
    private BukkitTask task;

    private ScenarioNpcs(ScenarioScene scene, List<NavPoint> loop, NpcMoverFactory factory, NavigationSettings settings) {
        this.scene = scene;
        this.loop = HopPlanner.resampleKeepingCorners(loop, 1.0);
        this.factory = factory;
        this.settings = settings;
        metrics.setEnabled(true);
        BukkitNavWorld world = new BukkitNavWorld(null);
        Consumer<String> logLine = line -> log.add(new LogLine(tick, line));
        service = new NpcNavigationService(new NpcNavigationService.Ports(this::plan, this::prepare, world, metrics,
            System::nanoTime, logLine, AccessRules.OPEN), 4);
        groups = new NavGroupService(new NavGroupService.Ports(service, world, AccessRules.OPEN, metrics,
            System::nanoTime, logLine));
    }

    /**
     * Starts NPC navigation on Citizens for a scene: fails the step when Citizens is not installed or not usable.
     *
     * @param loop     the scene's route line, floor points, once round its loop
     * @param settings the per-NPC settings (code defaults with the scenario's own values)
     */
    public static ScenarioNpcs start(ScenarioScene scene, List<NavPoint> loop, NavigationSettings settings) {
        CitizensBootstrap.unavailableReason().ifPresent(reason -> {
            throw new ScenarioAssertionError("NPC scenarios need Citizens: " + reason);
        });
        NpcMoverFactory factory = CitizensBootstrap.start(scene.services().plugin(),
            scene.uniqueName("npcs").replace('_', '-')).orElseThrow(
                () -> new ScenarioAssertionError("Citizens did not start for the scenario"));
        ScenarioNpcs npcs = new ScenarioNpcs(scene, loop, factory, settings);
        npcs.task = Bukkit.getScheduler().runTaskTimer(scene.services().plugin(), npcs::tick, 1L, 1L);
        return npcs;
    }

    /** Stops the ticking, cancels every order and removes the NPCs. Safe to call twice. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
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

    /** Writes an NPC's position and state to the run log every 5 ticks (for failure reasons). */
    public void trace(NpcMover mover) {
        traced = mover;
    }

    private void tick() {
        tick++;
        groups.tick();
        service.tick();
        if (traced != null && tick % 5 == 0) {
            NpcMover t = traced;
            log.add(new LogLine(tick, "trace " + t.name() + " " + t.position().map(p -> String.format(Locale.ROOT,
                "%.2f %.2f %.2f", p.x(), p.y(), p.z())).orElse("-") + " " + service.state(t.id()) + " moving "
                + t.isMoving()));
        }
        for (NpcMover mover : movers) {
            watchStill(mover);
        }
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
        if (at == null || follower || leaderWaits || state == null || state == NpcNavState.IDLE) {
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
        return CompletableFuture.completedFuture(RoadPlanner.RoadPlan.found(line));
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
        NavigationSettings.Scope scope = NavigationSettings.Scope.of(members.get(0).position().orElseThrow().world());
        GroupSettings base = GroupSettings.of(settings, scope);
        GroupSettings own = base.withFormation(new Formation(formation, base.formation().spacing(),
            base.formation().ranksWidth()));
        NavGroup group = groups.create(members.get(0), members.subList(1, members.size()), NpcAccessProfile.DEFAULT, own);
        groupList.add(group);
        patrolGroup(group, first, second);
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
