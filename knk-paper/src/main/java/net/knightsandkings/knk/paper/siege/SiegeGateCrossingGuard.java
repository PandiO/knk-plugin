package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.siege.SiegePreGameGate;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

/**
 * Siege DESIGN §8.5 "Crossing check" (PandiO/knk-workspace#215): keeps players who are not in the match out of
 * everything a gate closed before the match blocked off - <b>server-side</b>, independent of the fake blocks
 * ({@link SiegeGateViewService}), which only make it look and feel right for normal clients. The blocked cells
 * are the pre-game closed frames of every locked door ({@link SiegeGateViewService#blockedFor}); a door opened
 * for the player (#216) is left out.
 * <ul>
 *   <li><b>Walking</b> ({@link PlayerMoveEvent}): the player's hitbox is swept along the whole move
 *   ({@link SiegePreGameGate#crosses}), so sprinting, elytra, riptide, water flow and pistons can't skip a
 *   1-block frame. A crossing is refused ({@code setTo(from)}).</li>
 *   <li><b>Vehicles</b> ({@link VehicleMoveEvent}, which riders don't fire a move event for): the vehicle is put
 *   back and stopped, and its non-member riders are ejected on their side.</li>
 *   <li><b>Ender pearls and chorus fruit</b> ({@link PlayerTeleportEvent}): projectiles use the real blocks, so a
 *   pearl flies through a really open gate; a teleport whose straight path crosses a blocked frame is cancelled.
 *   Plugin and command teleports aren't checked.</li>
 * </ul>
 * Exempt: members (by the cells' own lobby), spectators, and whoever {@code exempt} says (staff in an active
 * OWNER/STAFF mode). Not covered on purpose: projectiles, mobs, pets and dropped items.
 */
public final class SiegeGateCrossingGuard implements Listener {

    /** At most one refusal message per player per this many milliseconds. */
    static final long MESSAGE_COOLDOWN_MS = 2000L;

    private final SiegeGateViewService view;
    private final Predicate<Player> exempt;
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    public SiegeGateCrossingGuard(SiegeGateViewService view, Predicate<Player> exempt) {
        this.view = view;
        this.exempt = exempt == null ? p -> false : exempt;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!view.hasBlockedCells()) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || to.getWorld() == null || !to.getWorld().equals(from.getWorld())) return;
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;
        if (!view.hasBlockedCells(to.getWorld())) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (!crosses(view.blockedFor(player, to.getWorld()), from, to)) return;
        Location back = from.clone();
        back.setYaw(to.getYaw());
        back.setPitch(to.getPitch());
        event.setTo(back);
        tell(player);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!view.hasBlockedCells()) return;
        Vehicle vehicle = event.getVehicle();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to.getWorld() == null || !to.getWorld().equals(from.getWorld()) || !view.hasBlockedCells(to.getWorld())) return;
        List<Player> crossing = new ArrayList<>();
        for (Entity passenger : vehicle.getPassengers()) {
            if (passenger instanceof Player player && !isExempt(player)
                    && crosses(view.blockedFor(player, to.getWorld()), from, to)) {
                crossing.add(player);
            }
        }
        if (crossing.isEmpty()) return;
        crossing.forEach(vehicle::removePassenger);
        vehicle.setVelocity(new Vector());
        vehicle.teleport(from);
        for (Player player : crossing) {
            Location back = from.clone();
            back.setYaw(player.getLocation().getYaw());
            back.setPitch(player.getLocation().getPitch());
            player.teleport(back);
            tell(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!view.hasBlockedCells() || !isCheckedCause(event.getCause())) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || to.getWorld() == null || !to.getWorld().equals(from.getWorld())) return;
        Player player = event.getPlayer();
        if (isExempt(player)) return;
        if (!crosses(view.blockedFor(player, to.getWorld()), from, to)) return;
        event.setCancelled(true);
        tell(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastMessage.remove(event.getPlayer().getUniqueId());
    }

    /** Ender pearls and chorus fruit (older name kept for servers that still report it). */
    static boolean isCheckedCause(PlayerTeleportEvent.TeleportCause cause) {
        String name = cause.name();
        return name.equals("ENDER_PEARL") || name.equals("CONSUMABLE_EFFECT") || name.equals("CHORUS_FRUIT");
    }

    private boolean isExempt(Player player) {
        return player.getGameMode() == GameMode.SPECTATOR || exempt.test(player);
    }

    private static boolean crosses(LongPredicate blocked, Location from, Location to) {
        return SiegePreGameGate.crosses(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ(), blocked);
    }

    private void tell(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN_MS) return;
        lastMessage.put(player.getUniqueId(), now);
        player.sendActionBar(SiegeMessages.bad("This gate is closed outside the siege."));
    }
}
