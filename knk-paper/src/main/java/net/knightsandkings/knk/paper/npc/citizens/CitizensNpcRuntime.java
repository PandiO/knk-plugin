package net.knightsandkings.knk.paper.npc.citizens;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.ai.PathfinderType;
import net.citizensnpcs.api.ai.StuckAction;
import net.citizensnpcs.api.ai.event.CancelReason;
import net.citizensnpcs.api.ai.event.NavigationCancelEvent;
import net.citizensnpcs.api.ai.event.NavigationCompleteEvent;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.NPCDespawnEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.core.npc.nav.NpcMover;
import net.knightsandkings.knk.core.npc.nav.NpcMoverFactory;

/**
 * K&K's NPCs on Citizens (BEHAVIOR_MODEL NAV-10): an in-memory registry (nothing in Citizens' saves; the K&K side is
 * the source of truth), player-shaped NPCs with the pinned movement settings, and Citizens' navigation and lifecycle
 * events turned into {@link NpcMover.MoverListener} calls. Main thread.
 */
final class CitizensNpcRuntime implements NpcMoverFactory, Listener {

    static final String REGISTRY = "knk-npc-navigation";

    private final Plugin plugin;
    private NPCRegistry registry;
    private final Map<Integer, CitizensNpcMover> movers = new ConcurrentHashMap<>();

    /** Never teleport (a teleport would skip a closed gate or a denied region): stop and report stuck instead. */
    private static final StuckAction NO_TELEPORT = (npc, navigator) -> false;

    CitizensNpcRuntime(Plugin plugin) {
        this.plugin = plugin;
        this.registry = CitizensAPI.createInMemoryNPCRegistry(REGISTRY);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public Optional<String> unavailableReason() {
        return registry == null ? Optional.of("NPC runtime stopped") : CitizensBootstrap.unavailableReason();
    }

    @Override
    public NpcMover spawn(String name, NavPoint at, MoverOptions options) {
        if (registry == null) {
            throw new IllegalStateException("NPC runtime stopped");
        }
        World world = Bukkit.getWorld(at.world());
        if (world == null) {
            throw new IllegalArgumentException("no world " + at.world());
        }
        NPC npc = registry.createNPC(EntityType.PLAYER, name);
        npc.data().set(NPC.Metadata.SHOULD_SAVE, false);
        // Citizens appends its own door examiner when this is on, and its "first PASSABLE wins" rule would let it
        // open a door K&K denies: door handling stays with K&K.
        npc.data().set(NPC.Metadata.PATHFINDER_OPEN_DOORS, false);
        npc.data().set(NPC.Metadata.COLLIDABLE, options.collidable());
        if (!npc.spawn(new Location(world, at.x(), at.y(), at.z()))) {
            registry.deregister(npc);
            throw new IllegalStateException("Citizens refused to spawn " + name + " at " + at);
        }
        NavigatorParameters params = npc.getNavigator().getDefaultParameters();
        params.pathfinderType(PathfinderType.valueOf(options.pathfinder().toUpperCase(Locale.ROOT)))
            .stationaryTicks(options.stationaryTicks())
            .stuckAction(NO_TELEPORT)
            .destinationTeleportMargin(-1)
            // Citizens' A* only finishes on the exact target block by default; arrive within 1.5 blocks
            .pathDistanceMargin(1.5);
        CitizensNpcMover mover = new CitizensNpcMover(npc, this::forget);
        movers.put(npc.getId(), mover);
        return mover;
    }

    int moverCount() {
        return movers.size();
    }

    void forget(CitizensNpcMover mover) {
        movers.remove(mover.id());
    }

    private CitizensNpcMover ours(NPC npc) {
        return npc != null && npc.getOwningRegistry() == registry ? movers.get(npc.getId()) : null;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onComplete(NavigationCompleteEvent event) {
        CitizensNpcMover mover = ours(event.getNPC());
        if (mover != null) {
            mover.listener().arrived(mover);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCancel(NavigationCancelEvent event) {
        CitizensNpcMover mover = ours(event.getNPC());
        // PLUGIN: our own stop() or a replaced target - not the engine giving up
        if (mover != null && event.getCancelReason() == CancelReason.STUCK) {
            mover.listener().stuck(mover);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDespawn(NPCDespawnEvent event) {
        CitizensNpcMover mover = ours(event.getNPC());
        if (mover != null && event.getReason() != DespawnReason.REMOVAL) {
            boolean chunk = event.getReason() == DespawnReason.CHUNK_UNLOAD;
            mover.despawnedByChunk = chunk;
            mover.listener().despawned(mover, chunk);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpawn(NPCSpawnEvent event) {
        CitizensNpcMover mover = ours(event.getNPC());
        if (mover != null && mover.despawnedByChunk) {
            mover.despawnedByChunk = false;
            // NPCSpawnEvent fires before the entity is in the world: tell the listener on the next tick
            Bukkit.getScheduler().runTask(plugin, () -> mover.listener().respawned(mover));
        }
    }

    void shutdown() {
        for (CitizensNpcMover mover : movers.values()) {
            mover.npc().destroy();
        }
        movers.clear();
        HandlerList.unregisterAll(this);
        if (registry != null) {
            registry.deregisterAll();
            CitizensAPI.removeNamedNPCRegistry(REGISTRY);
            registry = null;
        }
    }
}
