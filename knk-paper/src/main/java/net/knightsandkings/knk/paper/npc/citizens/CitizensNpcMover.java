package net.knightsandkings.knk.paper.npc.citizens;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.DoorExaminer;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.npc.NPC;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
import net.knightsandkings.knk.core.npc.nav.LocalLeg;
import net.knightsandkings.knk.core.npc.nav.NpcMover;

/** One K&K NPC on Citizens. Main thread. */
final class CitizensNpcMover implements NpcMover {

    private static final MoverListener NONE = new MoverListener() {
    };

    private final NPC npc;
    private final java.util.function.Consumer<CitizensNpcMover> onRemove;
    private volatile MoverListener listener = NONE;
    /** Set by the runtime when the last despawn was a chunk unload, so the next spawn is a resume. */
    boolean despawnedByChunk;

    /** The dry examiner of an NPC spawned {@code dry} (#490), or null. */
    private final DryBlockExaminer dry;

    CitizensNpcMover(NPC npc, java.util.function.Consumer<CitizensNpcMover> onRemove) {
        this(npc, onRemove, null);
    }

    CitizensNpcMover(NPC npc, java.util.function.Consumer<CitizensNpcMover> onRemove, DryBlockExaminer dry) {
        this.npc = npc;
        this.onRemove = onRemove;
        this.dry = dry;
    }

    NPC npc() {
        return npc;
    }

    /** Engine jumps held back since spawn ({@link CitizensNpcRuntime#jumpPower}). */
    private long stepHopsHeld;

    void stepHopHeld() {
        stepHopsHeld++;
    }

    @Override
    public long stepHopsHeld() {
        return stepHopsHeld;
    }

    MoverListener listener() {
        return listener;
    }

    @Override
    public int id() {
        return npc.getId();
    }

    @Override
    public String name() {
        return npc.getName();
    }

    @Override
    public boolean isSpawned() {
        return npc.isSpawned();
    }

    @Override
    public Optional<NavPoint> position() {
        if (!npc.isSpawned()) {
            return Optional.empty();
        }
        Location at = npc.getEntity().getLocation();
        return Optional.of(new NavPoint(at.getWorld().getName(), at.getX(), at.getY(), at.getZ()));
    }

    @Override
    public boolean onGround() {
        Entity entity = npc.getEntity();
        return entity != null && entity.isOnGround();
    }

    @Override
    public double fallDistance() {
        Entity entity = npc.getEntity();
        return entity == null ? Double.NaN : entity.getFallDistance();
    }

    @Override
    public double verticalSpeed() {
        Entity entity = npc.getEntity();
        return entity == null ? Double.NaN : entity.getVelocity().getY();
    }

    /**
     * Puts the body somewhere else at once, through Citizens: a pushed NPC's teleport home (link M7), a scenario
     * pushing an NPC into a corner. The running order stops first.
     */
    @Override
    public boolean teleport(NavPoint feet) {
        World world = Bukkit.getWorld(feet.world());
        if (!npc.isSpawned() || world == null) {
            return false;
        }
        if (npc.getNavigator().isNavigating()) {
            npc.getNavigator().cancelNavigation();
        }
        npc.teleport(new Location(world, feet.x(), feet.y(), feet.z()),
            org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        return true;
    }

    @Override
    public void follow(List<NavPoint> waypoints) {
        if (!npc.isSpawned() || waypoints.isEmpty()) {
            return;
        }
        List<Vector> points = new ArrayList<>(waypoints.size());
        World world = Bukkit.getServer() == null ? null : Bukkit.getWorld(waypoints.get(0).world());
        for (NavPoint p : waypoints) {
            points.add(throughDoorway(world, p));
        }
        npc.getNavigator().setTarget(points);
    }

    /**
     * A waypoint in a cell with an open door or fence gate at the feet goes to the middle of the opening its panel
     * leaves (link C1, PandiO/knk-workspace#700): a body passes an open door only within about 0.1 of that middle.
     */
    static Vector throughDoorway(World world, NavPoint p) {
        if (world != null) {
            int x = (int) Math.floor(p.x());
            int y = (int) Math.floor(p.y() + 0.01);
            int z = (int) Math.floor(p.z());
            if (world.isChunkLoaded(x >> 4, z >> 4)) {
                double[] centre = net.knightsandkings.knk.paper.npc.nav.BukkitNavWorld.openingCentre(
                    world.getBlockAt(x, y, z));
                if (centre[0] != 0.5 || centre[1] != 0.5) {
                    return new Vector(x + centre[0], p.y(), z + centre[1]);
                }
            }
        }
        return new Vector(p.x(), p.y(), p.z());
    }

    /**
     * The waypoints with {@code margin} as Citizens' distance margin for this list only (implementation plan 4b.2
     * item 2): set on the navigator's local parameters right after the target, so the default parameters, and with
     * them every local leg, keep Citizens' own margin.
     */
    @Override
    public void follow(List<NavPoint> waypoints, double margin) {
        if (!npc.isSpawned() || waypoints.isEmpty()) {
            return;
        }
        follow(waypoints);
        if (npc.getNavigator().isNavigating()) {
            npc.getNavigator().getLocalParameters().distanceMargin(margin);
        }
    }

    /**
     * Why Citizens gave up the running order (implementation plan 4b.2 item 7.3), read in its cancel event while the
     * strategy is still set: {@code NO_PATH} when its path search has no path (an unreachable target), otherwise
     * {@code STATIONARY} (its stationary check; a waypoint list always has its path).
     */
    StuckKind stuckKind() {
        net.citizensnpcs.api.ai.PathStrategy strategy = npc.getNavigator().getPathStrategy();
        Iterable<Vector> path = strategy == null ? null : strategy.getPath();
        return path == null || !path.iterator().hasNext() ? StuckKind.NO_PATH : StuckKind.STATIONARY;
    }

    @Override
    public void walkTo(NavPoint target) {
        if (!npc.isSpawned()) {
            return;
        }
        World world = Bukkit.getWorld(target.world());
        if (world == null) {
            return;
        }
        npc.getNavigator().setTarget(new Location(world, target.x(), target.y(), target.z()));
        if (dry != null) {
            // An examiner Citizens appends when the target is set (its MinecraftBlockExaminer) would answer PASSABLE
            // for water before the dry veto is asked: only the dry one and the cost-only avoid-water one stay.
            stripForeignExaminers(npc.getNavigator().getLocalParameters(), dry);
        }
    }

    /**
     * Citizens' A* under K&K's rules: {@link KnkBlockExaminer} (the leg's access in front of Citizens' own block and,
     * when the leg allows doors, door examiners) is the only examiner. Citizens may still append examiners of its own
     * when the target is set; any but its cost-only one would overrule a K&K veto ("first PASSABLE wins"), so they are
     * removed and reported. The search reads the examiners when it runs, after this method.
     */
    @Override
    public void walkTo(NavPoint target, LocalLeg leg) {
        if (!npc.isSpawned()) {
            return;
        }
        World world = Bukkit.getWorld(target.world());
        if (world == null) {
            return;
        }
        NavigatorParameters params = npc.getNavigator().getDefaultParameters();
        params.clearExaminers();
        List<BlockExaminer> inner = new ArrayList<>();
        inner.add(new MinecraftBlockExaminer(npc));
        if (leg.allowDoors()) {
            inner.add(new DoorExaminer());
        }
        KnkBlockExaminer ours = new KnkBlockExaminer(leg, inner);
        params.examiner(ours);
        npc.getNavigator().setTarget(new Location(world, target.x(), target.y(), target.z()));
        String foreign = stripForeignExaminers(npc.getNavigator().getLocalParameters(), ours);
        if (foreign != null) {
            listener.engineRuleRemoved(this, foreign);
        }
    }

    /**
     * Leaves {@code ours} and Citizens' cost-only examiner in {@code local}; returns the names of the others, which
     * were removed, or null when there were none.
     */
    static String stripForeignExaminers(NavigatorParameters local, BlockExaminer ours) {
        BlockExaminer[] used = local.examiners();
        StringBuilder names = new StringBuilder();
        List<BlockExaminer> keep = new ArrayList<>();
        for (BlockExaminer examiner : used) {
            if (examiner == ours || isCitizensCostOnly(examiner)) {
                keep.add(examiner);
            } else {
                names.append(names.length() == 0 ? "" : "+").append(examiner.getClass().getSimpleName());
            }
        }
        if (names.length() == 0) {
            return null;
        }
        local.clearExaminers();
        keep.forEach(local::examiner);
        return names.toString();
    }

    /**
     * Citizens' own avoid-water cost examiner (an anonymous class of its A* strategy): it answers IGNORE to
     * passable and standable, so it cannot overrule a K&K veto and stays.
     */
    static boolean isCitizensCostOnly(BlockExaminer examiner) {
        return examiner.getClass().getName().startsWith("net.citizensnpcs.npc.ai.AStarNavigationStrategy$");
    }

    /**
     * The rest of Citizens' path for the running order, from the point nearest the NPC on (Citizens keeps the whole
     * path from its start). Empty when Citizens has no path yet or its strategy has none to show.
     */
    @Override
    public List<NavPoint> pathAhead() {
        if (!npc.isSpawned() || !npc.getNavigator().isNavigating()) {
            return List.of();
        }
        net.citizensnpcs.api.ai.PathStrategy strategy = npc.getNavigator().getPathStrategy();
        Iterable<Vector> path = strategy == null ? null : strategy.getPath();
        if (path == null) {
            return List.of();
        }
        Location at = npc.getEntity().getLocation();
        return aheadOf(at.getWorld().getName(), at.toVector(), path);
    }

    /** {@code path} from the point nearest {@code feet} on, as feet positions at block centres. */
    static List<NavPoint> aheadOf(String world, Vector feet, Iterable<Vector> path) {
        List<Vector> points = new ArrayList<>();
        path.forEach(points::add);
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            double d = points.get(i).distanceSquared(feet);
            if (d < best) {
                best = d;
                nearest = i;
            }
        }
        List<NavPoint> ahead = new ArrayList<>(points.size() - nearest);
        for (Vector p : points.subList(nearest, points.size())) {
            ahead.add(new NavPoint(world, p.getBlockX() + 0.5, p.getBlockY(), p.getBlockZ() + 0.5));
        }
        return ahead;
    }

    /** Citizens' speed modifier: on the default parameters for the next orders and on the running one. */
    @Override
    public void setSpeedFactor(double factor) {
        float modifier = (float) Math.max(0.1, Math.min(3, factor));
        npc.getNavigator().getDefaultParameters().speedModifier(modifier);
        if (npc.isSpawned() && npc.getNavigator().isNavigating()) {
            npc.getNavigator().getLocalParameters().speedModifier(modifier);
        }
    }

    @Override
    public void stop() {
        if (npc.isSpawned() && npc.getNavigator().isNavigating()) {
            npc.getNavigator().cancelNavigation();
        }
    }

    @Override
    public boolean isMoving() {
        return npc.isSpawned() && npc.getNavigator().isNavigating();
    }

    @Override
    public void remove() {
        onRemove.accept(this);
        npc.destroy();
    }

    @Override
    public void setListener(MoverListener listener) {
        this.listener = Objects.requireNonNullElse(listener, NONE);
    }
}
