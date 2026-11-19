package net.knightsandkings.knk.paper.npc.citizens;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
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
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.NPCCollisionEvent;
import net.citizensnpcs.api.event.NPCDespawnEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;
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

    private final String registryName;

    CitizensNpcRuntime(Plugin plugin) {
        this(plugin, REGISTRY);
    }

    /** @param registryName the in-memory registry's name (a scenario uses its own, beside the module's) */
    CitizensNpcRuntime(Plugin plugin, String registryName) {
        this.plugin = plugin;
        this.registryName = registryName;
        this.registry = CitizensAPI.createInMemoryNPCRegistry(registryName);
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
        DryBlockExaminer dry = null;
        if (options.dry()) {
            // #490: the test walkers' own searches (walkTo without a leg) never go through or over water or lava.
            dry = new DryBlockExaminer(List.of(new MinecraftBlockExaminer(npc)));
            params.avoidWater(true).clearExaminers().examiner(dry);
        }
        CitizensNpcMover mover = new CitizensNpcMover(npc, this::forget, dry);
        npc.data().set(NPC.Metadata.JUMP_POWER_SUPPLIER,
            (java.util.function.Function<NPC, Float>) n -> jumpPower(n, mover));
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
            mover.listener().stuck(mover, mover.stuckKind());
        }
    }

    /**
     * Citizens calls this for every push of an NPC's body by another entity (every tick while they overlap): a contact
     * for K&K's push detection (link M7). A player exempt from the NPC's collision never pushes it, so never shows here.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCollision(NPCCollisionEvent event) {
        CitizensNpcMover mover = ours(event.getNPC());
        Entity other = event.getCollidedWith();
        if (mover != null && other != null) {
            mover.listener().contact(mover, contactOf(other, NpcMover.ContactKind.OTHER));
        }
    }

    /** An attack's knockback (Paper's event, not cancelled: it stays, link M7); the NPC walks back after it. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttackPush(io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent event) {
        CitizensNpcMover mover = registry == null ? null : ours(registry.getNPC(event.getEntity()));
        if (mover != null) {
            Entity by = event.getPushedBy();
            mover.listener().contact(mover, new NpcMover.Contact(NpcMover.ContactKind.ATTACK,
                by == null ? null : contactId(by), by instanceof Player && !by.hasMetadata("NPC")));
        }
    }

    /**
     * The jump power Citizens asks for whenever one of K&K's NPCs jumps ({@code NPC.Metadata.JUMP_POWER_SUPPLIER}):
     * none for the engine's stair hop (link J1, PandiO/knk-workspace#695, {@link StepHop}), so a body on a stair's
     * lower half walks onto the upper half instead of hopping; otherwise the body's own (its jump strength, halved on
     * a honey block as in vanilla). Citizens' human NPC jumps through its own code, not Paper's {@code EntityJumpEvent}.
     */
    static float jumpPower(NPC npc, CitizensNpcMover mover) {
        Entity entity = npc.getEntity();
        if (!(entity instanceof org.bukkit.entity.LivingEntity body)) {
            return 0.42f;
        }
        Location at = body.getLocation();
        org.bukkit.block.Block block = at.getBlock();
        if (StepHop.isStepHop(at.getY(), block.getY(), block.getCollisionShape().getBoundingBoxes(), stepHeight(body))) {
            if (mover != null) {
                mover.stepHopHeld();
            }
            return 0f;
        }
        org.bukkit.attribute.AttributeInstance strength = body.getAttribute(org.bukkit.attribute.Attribute.JUMP_STRENGTH);
        double power = strength == null ? 0.42 : strength.getValue();
        // vanilla's block jump factor: the block at the feet, else the one under them (only honey has one)
        boolean honey = block.getType() == org.bukkit.Material.HONEY_BLOCK
            || at.clone().subtract(0, 0.5000001, 0).getBlock().getType() == org.bukkit.Material.HONEY_BLOCK;
        return (float) (honey ? power * 0.5 : power);
    }

    /** The body's step height (a player's 0.6 unless an attribute changed it). */
    static double stepHeight(org.bukkit.entity.LivingEntity body) {
        org.bukkit.attribute.AttributeInstance step = body.getAttribute(org.bukkit.attribute.Attribute.STEP_HEIGHT);
        return step == null ? 0.6 : step.getValue();
    }

    /** A body's contact: a player (not a Citizens NPC), an NPC (any Citizens NPC), or any other entity. */
    static NpcMover.Contact contactOf(Entity other, NpcMover.ContactKind otherwise) {
        if (other.hasMetadata("NPC")) {
            return new NpcMover.Contact(NpcMover.ContactKind.NPC, contactId(other));
        }
        if (other instanceof Player) {
            return new NpcMover.Contact(NpcMover.ContactKind.PLAYER, contactId(other));
        }
        return new NpcMover.Contact(otherwise, contactId(other));
    }

    private static String contactId(Entity e) {
        return e.getUniqueId().toString();
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
            CitizensAPI.removeNamedNPCRegistry(registryName);
            registry = null;
        }
    }
}
