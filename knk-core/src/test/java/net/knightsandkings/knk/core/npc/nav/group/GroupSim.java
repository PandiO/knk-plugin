package net.knightsandkings.knk.core.npc.nav.group;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import net.knightsandkings.knk.core.npc.nav.AccessRules;
import net.knightsandkings.knk.core.npc.nav.AccessSubject;
import net.knightsandkings.knk.core.npc.nav.LocalLeg;
import net.knightsandkings.knk.core.npc.nav.LocalLegPreparer;
import net.knightsandkings.knk.core.npc.nav.LocalLegPreparer.Prepared;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavWorld;
import net.knightsandkings.knk.core.npc.nav.NavigationResult;
import net.knightsandkings.knk.core.npc.nav.NpcAccessProfile;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationService;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner;
import net.knightsandkings.knk.core.npc.nav.RoadPlanner.RoadPlan;
import net.knightsandkings.knk.core.roads.walk.CellAccess;

/**
 * A small simulated server for the group tests: a flat world with a road along x at z = 0.5, movers that really walk
 * their orders tick by tick, the real {@link NpcNavigationService} over a counting road planner and leg preparer,
 * and the {@link NavGroupService} on top. Plans are answered one tick after they were asked.
 */
final class GroupSim {

    static final String WORLD = "world";
    /** Blocks a mover walks per tick at normal speed (about 4.3 blocks per second). */
    static final double SPEED = 0.215;

    /** Flat at floor y 64; columns can lose their floor (walls) and have regions. */
    static final class SimWorld implements NavWorld {
        BiPredicate<Integer, Integer> noFloor = (x, z) -> false;
        BiFunction<Integer, Integer, Set<String>> regions = (x, z) -> Set.of();

        @Override
        public boolean isLoaded(String world, int x, int z) {
            return true;
        }

        @Override
        public boolean isStandableFloor(String world, int x, int floorY, int z) {
            return floorY == 64 && !noFloor.test(x, z);
        }

        @Override
        public Set<String> regionsAt(String world, int x, int feetY, int z) {
            return regions.apply(x, z);
        }
    }

    /** The current rules: regions nobody may enter (unless already inside) and gate doors that are closed. */
    static final class SimRules implements AccessRules {
        final Set<String> deniedRegions = new HashSet<>();
        final Set<Integer> closedGates = new HashSet<>();

        @Override
        public Predicate<String> mayEnter(AccessSubject subject) {
            Set<String> current = subject.currentRegions();
            return region -> current.contains(region) || !deniedRegions.contains(region);
        }

        @Override
        public boolean gateBlocks(AccessSubject subject, int doorId) {
            return closedGates.contains(doorId);
        }
    }

    /** A mover that walks its orders in straight lines, a step per tick. */
    final class SimMover implements NpcMover {
        final int id;
        NavPoint position;
        boolean spawned = true;
        MoverListener listener;
        private final List<NavPoint> route = new ArrayList<>();
        boolean moving;
        /** Does not move although it has an order (stuck against something). */
        boolean frozen;
        double speedFactor = 1;
        double fastestFactor = 1;
        int follows;
        int walks;

        SimMover(int id, NavPoint feet) {
            this.id = id;
            this.position = feet;
        }

        @Override
        public int id() {
            return id;
        }

        @Override
        public String name() {
            return "npc-" + id;
        }

        @Override
        public boolean isSpawned() {
            return spawned;
        }

        @Override
        public Optional<NavPoint> position() {
            return spawned ? Optional.of(position) : Optional.empty();
        }

        @Override
        public boolean onGround() {
            return true;
        }

        @Override
        public void follow(List<NavPoint> waypoints) {
            follows++;
            route.clear();
            route.addAll(waypoints);
            moving = !route.isEmpty();
        }

        @Override
        public void walkTo(NavPoint target) {
            throw new AssertionError("navigation never walks without K&K's rules");
        }

        @Override
        public void walkTo(NavPoint target, LocalLeg leg) {
            walks++;
            route.clear();
            route.add(target);
            moving = true;
        }

        @Override
        public void setSpeedFactor(double factor) {
            speedFactor = factor;
            fastestFactor = Math.max(fastestFactor, factor);
        }

        @Override
        public void stop() {
            route.clear();
            moving = false;
        }

        @Override
        public boolean isMoving() {
            return moving;
        }

        @Override
        public void remove() {
            spawned = false;
        }

        @Override
        public void setListener(MoverListener listener) {
            this.listener = listener;
        }

        void step() {
            if (!moving || frozen || !spawned) {
                return;
            }
            double left = SPEED * speedFactor;
            while (left > 0 && !route.isEmpty()) {
                NavPoint next = route.get(0);
                double d = position.horizontalDistance(next);
                if (d <= left) {
                    position = next;
                    route.remove(0);
                    left -= d;
                } else {
                    position = new NavPoint(WORLD, position.x() + (next.x() - position.x()) * left / d, next.y(),
                        position.z() + (next.z() - position.z()) * left / d);
                    left = 0;
                }
            }
            if (route.isEmpty()) {
                moving = false;
                if (listener != null) {
                    listener.arrived(this);
                }
            }
        }
    }

    private record RoadAsk(AccessSubject subject, NavPoint from, NavPoint to, CompletableFuture<RoadPlan> answer) {
    }

    private record LegAsk(AccessSubject subject, CompletableFuture<Prepared> answer) {
    }

    @FunctionalInterface
    interface Road {
        RoadPlan plan(AccessSubject subject, NavPoint from, NavPoint to);
    }

    private static final LocalLeg LEG = new LocalLeg(CellAccess.OPEN, -10000, 0, -10000, 10000, 255, 10000, true);

    final SimWorld world = new SimWorld();
    final SimRules rules = new SimRules();
    final NpcNavigationMetrics metrics = new NpcNavigationMetrics();
    final List<String> log = new ArrayList<>();
    final List<SimMover> movers = new ArrayList<>();
    final NpcNavigationService service;
    final NavGroupService groups;
    /** The road: a straight line from where the subject stands to the goal unless a test says otherwise. */
    Road road = (subject, from, to) -> RoadPlan.found(List.of(from, to));
    /** Movers (by name) whose local legs cannot be prepared. */
    final Set<String> failingLegs = new HashSet<>();
    private final List<RoadAsk> roadAsks = new ArrayList<>();
    private final List<LegAsk> legAsks = new ArrayList<>();
    int roadPlans;
    int localPlans;
    /** The most plans waiting or running at the same time, seen at the end of a tick. */
    int mostPlansAtOnce;
    long tick;
    private int nextId = 1;

    GroupSim() {
        metrics.setEnabled(true);
        RoadPlanner roads = (subject, from, to) -> {
            roadPlans++;
            CompletableFuture<RoadPlan> answer = new CompletableFuture<>();
            roadAsks.add(new RoadAsk(subject, from, to, answer));
            return answer;
        };
        LocalLegPreparer legs = (subject, feet, target) -> {
            localPlans++;
            CompletableFuture<Prepared> answer = new CompletableFuture<>();
            legAsks.add(new LegAsk(subject, answer));
            return answer;
        };
        service = new NpcNavigationService(new NpcNavigationService.Ports(roads, legs, world, metrics, null, log::add,
            rules), 4, 10);
        groups = new NavGroupService(new NavGroupService.Ports(service, world, rules, metrics, null, log::add));
    }

    static NavPoint feet(double x, double z) {
        return new NavPoint(WORLD, x, 65, z);
    }

    static NavPoint floor(double x, double z) {
        return new NavPoint(WORLD, x, 64, z);
    }

    /** A mover under navigation, standing at {@code feet}. */
    SimMover spawn(NavPoint feet) {
        SimMover mover = new SimMover(nextId++, feet);
        movers.add(mover);
        service.register(mover, NpcAccessProfile.DEFAULT, NpcNavigationService.Settings.defaults());
        return mover;
    }

    /**
     * A group of {@code n}: the leader on the road at x = 0.5, the followers in a bunch just behind it (as the test
     * driver spawns them near the route's start).
     */
    NavGroup group(int n, GroupSettings settings) {
        SimMover leader = spawn(feet(0.5, 0.5));
        List<NpcMover> followers = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            followers.add(spawn(feet(-0.5 - (i % 4), -0.5 + (i % 3))));
        }
        return groups.create(leader, followers, NpcAccessProfile.DEFAULT, settings);
    }

    void tick() {
        tick++;
        groups.tick();
        service.tick();
        mostPlansAtOnce = Math.max(mostPlansAtOnce, service.queued() + service.plansInFlight());
        for (RoadAsk ask : List.copyOf(roadAsks)) {
            roadAsks.remove(ask);
            ask.answer().complete(road.plan(ask.subject(), ask.from(), ask.to()));
        }
        for (LegAsk ask : List.copyOf(legAsks)) {
            legAsks.remove(ask);
            ask.answer().complete(failingLegs.contains(ask.subject().name())
                ? Prepared.of(LocalLegPreparer.Status.FAILED, "test") : Prepared.ready(LEG));
        }
        for (SimMover mover : movers) {
            mover.step();
        }
    }

    void run(int ticks) {
        for (int i = 0; i < ticks; i++) {
            tick();
        }
    }

    /** Runs until {@code done} holds; false when it did not within {@code maxTicks}. */
    boolean runUntil(java.util.function.BooleanSupplier done, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            if (done.getAsBoolean()) {
                return true;
            }
            tick();
        }
        return done.getAsBoolean();
    }

    /** Collects a group order's results. */
    static final class Results implements java.util.function.Consumer<NavigationResult> {
        final List<NavigationResult> all = new ArrayList<>();

        @Override
        public void accept(NavigationResult result) {
            all.add(result);
        }

        boolean ended() {
            return !all.isEmpty() && !(all.get(all.size() - 1) instanceof NavigationResult.Blocked);
        }

        NavigationResult last() {
            return all.get(all.size() - 1);
        }

        long blocked() {
            return all.stream().filter(NavigationResult.Blocked.class::isInstance).count();
        }
    }
}
