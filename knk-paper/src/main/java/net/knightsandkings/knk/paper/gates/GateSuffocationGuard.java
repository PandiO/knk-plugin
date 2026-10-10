package net.knightsandkings.knk.paper.gates;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.gates.GateSpatialIndex;
import net.knightsandkings.knk.core.gates.safety.GateSafeSpotFinder.CellFilter;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.logging.Logger;

/**
 * KNG-106: an entity found inside a gate door's blocks - after a teleport, on login, or because a
 * push missed it - is moved out at once instead of suffocating.
 * <ul>
 *   <li>Suffocation damage while any block the entity's box overlaps is a gate block (the
 *       {@link GateSpatialIndex}): the entity is moved out by {@link EntityEvacuator} and, unless
 *       {@code gates.safety.door-suffocation-damage} is true, the damage is cancelled.</li>
 *   <li>A player who joins or teleports into gate blocks is moved out one tick later, before the
 *       first suffocation tick.</li>
 * </ul>
 * <b>Decision for review (2026-10-10):</b> gate door blocks never cause suffocation damage by
 * default ({@code door-suffocation-damage: false}); a door is a mechanism, not a trap, and the
 * entity is moved out in the same tick anyway. Set it to true to keep the vanilla damage on top of
 * the move. Other suffocation (sand, gravel, ordinary walls) is untouched.
 */
public class GateSuffocationGuard implements Listener {
    private static final Logger LOGGER = Logger.getLogger(GateSuffocationGuard.class.getName());
    private static final double EPSILON = 1e-3;

    private final Plugin plugin;
    private final GateManager gateManager;
    private final boolean doorSuffocationDamage;

    public GateSuffocationGuard(Plugin plugin, GateManager gateManager, boolean doorSuffocationDamage) {
        this.plugin = plugin;
        this.gateManager = gateManager;
        this.doorSuffocationDamage = doorSuffocationDamage;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.SUFFOCATION) {
            return;
        }
        Entity entity = event.getEntity();
        Integer gateId = gateInside(entity);
        if (gateId == null) {
            return;
        }
        if (!doorSuffocationDamage) {
            event.setCancelled(true);
        }
        moveOut(entity, gateId);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        checkNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        checkNextTick(event.getPlayer());
    }

    private void checkNextTick(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                return;
            }
            Integer gateId = gateInside(player);
            if (gateId != null) {
                moveOut(player, gateId);
            }
        });
    }

    /**
     * Moves the entity out of the door: anywhere standable outside the door's region and every gate
     * block. Returns whether it moved.
     */
    boolean moveOut(Entity entity, int gateId) {
        CachedGateDoor gate = gateManager.getGate(gateId);
        if (gate == null) {
            return false;
        }
        CellFilter avoid = CellFilter.inside(GateDoorBounds.of(gate, gateManager));
        boolean moved = EntityEvacuator.evacuate(entity, gate, gateManager, avoid);
        if (moved) {
            LOGGER.info("[GateSafety] Moved " + describe(entity) + " out of the blocks of gate door '" + gate.getName()
                + "' (#" + gate.getId() + ")");
        } else {
            LOGGER.warning("[GateSafety] " + describe(entity) + " is inside the blocks of gate door '" + gate.getName()
                + "' (#" + gate.getId() + ") and no safe spot is near; left in place");
        }
        return moved;
    }

    /** The id of a gate whose block the entity's box overlaps, or null. */
    Integer gateInside(Entity entity) {
        World world = entity.getWorld();
        GateSpatialIndex index = gateManager.getSpatialIndex();
        if (world == null || index == null) {
            return null;
        }
        BoundingBox box = entity.getBoundingBox();
        int minX = (int) Math.floor(box.getMinX() + EPSILON);
        int maxX = (int) Math.floor(box.getMaxX() - EPSILON);
        int minY = (int) Math.floor(box.getMinY() + EPSILON);
        int maxY = (int) Math.floor(box.getMaxY() - EPSILON);
        int minZ = (int) Math.floor(box.getMinZ() + EPSILON);
        int maxZ = (int) Math.floor(box.getMaxZ() - EPSILON);
        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Integer gateId = index.lookup(world.getName(), x, y, z);
                    if (gateId != null) {
                        return gateId;
                    }
                }
            }
        }
        return null;
    }

    private static String describe(Entity entity) {
        return entity instanceof Player player ? "player " + player.getName() : String.valueOf(entity.getType());
    }
}
