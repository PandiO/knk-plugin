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

import net.citizensnpcs.api.npc.NPC;
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
