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
import net.knightsandkings.knk.core.npc.nav.LocalLeg;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
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

    CitizensNpcMover(NPC npc, java.util.function.Consumer<CitizensNpcMover> onRemove) {
        this.npc = npc;
        this.onRemove = onRemove;
    }

    NPC npc() {
        return npc;
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
    public void follow(List<NavPoint> waypoints) {
        if (!npc.isSpawned() || waypoints.isEmpty()) {
            return;
        }
        List<Vector> points = new ArrayList<>(waypoints.size());
        for (NavPoint p : waypoints) {
            points.add(new Vector(p.x(), p.y(), p.z()));
        }
        npc.getNavigator().setTarget(points);
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
