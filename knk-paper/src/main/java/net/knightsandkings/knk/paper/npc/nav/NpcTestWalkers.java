package net.knightsandkings.knk.paper.npc.nav;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Location;
import org.bukkit.World;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NavigationSettings;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcMoverFactory;
import net.knightsandkings.knk.core.npc.nav.NpcNavState;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics;
import net.knightsandkings.knk.core.npc.nav.NpcNavigationMetrics.Event;

/**
 * Phase 1 test NPCs (PandiO/knk-workspace#359): player-shaped NPCs that walk to random points near where they were
 * spawned with the engine's own local search, enough to exercise the event audit and the metrics. Not a gameplay
 * feature; Phase 2 replaces the wandering with route following. Main thread.
 */
public final class NpcTestWalkers {

    private record Walker(NpcMover mover, Location center, int radius) {
    }

    private final NpcMoverFactory factory;
    private final NavigationSettings settings;
    private final NpcNavigationMetrics metrics;
    private final Map<Integer, Walker> walkers = new LinkedHashMap<>();
    private int nextName = 1;

    public NpcTestWalkers(NpcMoverFactory factory, NavigationSettings settings, NpcNavigationMetrics metrics) {
        this.factory = factory;
        this.settings = settings;
        this.metrics = metrics;
    }

    public int count() {
        return walkers.size();
    }

    /** Spawns {@code n} walkers around {@code center}; returns how many spawned. */
    public int spawn(Location center, int n, int radius) {
        World world = center.getWorld();
        NavigationSettings.Scope scope = NavigationSettings.Scope.of(world.getName());
        NpcMoverFactory.MoverOptions options = new NpcMoverFactory.MoverOptions(
            settings.value(NavigationSettings.PATHFINDER, scope),
            settings.value(NavigationSettings.STATIONARY_TICKS, scope),
            settings.value(NavigationSettings.COLLIDABLE, scope));
        int made = 0;
        for (int i = 0; i < n; i++) {
            Optional<Location> at = randomFloor(center, Math.min(radius, 4));
            if (at.isEmpty()) {
                continue;
            }
            NpcMover mover = factory.spawn("knk-test-" + nextName++, toPoint(at.get()), options);
            Walker walker = new Walker(mover, center.clone(), radius);
            mover.setListener(new NpcMover.MoverListener() {
                @Override
                public void arrived(NpcMover m) {
                    metrics.count(Event.ARRIVED);
                    walkOn(walker);
                }

                @Override
                public void stuck(NpcMover m) {
                    metrics.count(Event.STUCK_ENGINE);
                    walkOn(walker);
                }

                @Override
                public void despawned(NpcMover m, boolean chunkUnload) {
                    if (chunkUnload) {
                        metrics.count(Event.PAUSED);
                    }
                }

                @Override
                public void respawned(NpcMover m) {
                    metrics.count(Event.RESUMED);
                    walkOn(walker);
                }
            });
            walkers.put(mover.id(), walker);
            metrics.count(Event.SPAWNED);
            walkOn(walker);
            made++;
        }
        return made;
    }

    public int clear() {
        int n = walkers.size();
        for (Walker walker : List.copyOf(walkers.values())) {
            walker.mover().remove();
            metrics.count(Event.REMOVED);
        }
        walkers.clear();
        return n;
    }

    /** NPC counts by world and state, for the metrics snapshot and the status command. */
    public Map<String, Map<NpcNavState, Integer>> states() {
        Map<String, Map<NpcNavState, Integer>> out = new HashMap<>();
        for (Walker walker : walkers.values()) {
            NpcNavState state = !walker.mover().isSpawned() ? NpcNavState.PAUSED
                : walker.mover().isMoving() ? NpcNavState.MOVING : NpcNavState.WAITING;
            out.computeIfAbsent(walker.center().getWorld().getName(), w -> new EnumMap<>(NpcNavState.class))
                .merge(state, 1, Integer::sum);
        }
        return out;
    }

    private void walkOn(Walker walker) {
        if (!walker.mover().isSpawned()) {
            return;
        }
        randomFloor(walker.center(), walker.radius()).ifPresent(target -> walker.mover().walkTo(toPoint(target)));
    }

    /** A random standable spot within {@code radius} of {@code center}: solid floor, two free blocks above. */
    static Optional<Location> randomFloor(Location center, int radius) {
        World world = center.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 16; attempt++) {
            int x = center.getBlockX() + random.nextInt(-radius, radius + 1);
            int z = center.getBlockZ() + random.nextInt(-radius, radius + 1);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            for (int dy = 6; dy >= -6; dy--) {
                int y = center.getBlockY() - 1 + dy;
                if (world.getBlockAt(x, y, z).getType().isSolid() && world.getBlockAt(x, y + 1, z).isPassable()
                    && world.getBlockAt(x, y + 2, z).isPassable()) {
                    return Optional.of(new Location(world, x + 0.5, y + 1, z + 0.5));
                }
            }
        }
        return Optional.empty();
    }

    static NavPoint toPoint(Location l) {
        return new NavPoint(l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
    }
}
