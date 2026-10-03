package net.knightsandkings.knk.paper.statistics;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;

import io.papermc.paper.event.player.AsyncChatEvent;

/**
 * AFK activity signals (DESIGN.md §F.2): looking around (yaw or pitch ≥ 1°), walking while not in a
 * vehicle and not in water, chat, commands, block break/place, interacting with a block or entity,
 * inventory clicks, attacking, toggling sneak/sprint. <b>Not</b> activity (anti-AFK-pool): movement in
 * a vehicle or in water (incl. pushes by water/pistons), taking damage, teleports. Move events are
 * throttled to one signal per player per second. Chat arrives off the main thread and is handed to
 * it ({@code mainThread}).
 */
public final class AfkActivityListener implements Listener {

    /** Smallest yaw/pitch change that counts as looking around. */
    static final float LOOK_DEGREES = 1.0f;
    static final long MOVE_SIGNAL_INTERVAL_MILLIS = 1_000L;

    private final StatisticsService service;
    private final Consumer<Runnable> mainThread;
    private final java.time.Clock clock;
    private final Map<UUID, long[]> lastMoveSignal = new HashMap<>();

    public AfkActivityListener(StatisticsService service, Consumer<Runnable> mainThread, java.time.Clock clock) {
        this.service = service;
        this.mainThread = mainThread;
        this.clock = clock;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!isActivity(from, to, player.isInsideVehicle(), player.isInWater())) {
            return;
        }
        long now = clock.millis();
        long[] last = lastMoveSignal.computeIfAbsent(player.getUniqueId(), id -> new long[]{Long.MIN_VALUE / 2});
        if (now - last[0] < MOVE_SIGNAL_INTERVAL_MILLIS && !service.isAfk(player.getUniqueId())) {
            return;
        }
        last[0] = now;
        service.activity(player);
    }

    /** Looking around always counts; position changes only on foot outside water. */
    static boolean isActivity(Location from, Location to, boolean inVehicle, boolean inWater) {
        if (angleDelta(from.getYaw(), to.getYaw()) >= LOOK_DEGREES || Math.abs(from.getPitch() - to.getPitch()) >= LOOK_DEGREES) {
            return true;
        }
        if (inVehicle || inWater) {
            return false;
        }
        return from.getX() != to.getX() || from.getZ() != to.getZ();
    }

    static float angleDelta(float a, float b) {
        float delta = Math.abs(a - b) % 360f;
        return delta > 180f ? 360f - delta : delta;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        mainThread.accept(() -> {
            if (player.isOnline()) {
                service.activity(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        // /afk decides itself (toggling): signalling first would end AFK and /afk would start it again.
        if (!isAfkCommand(event.getMessage())) {
            service.activity(event.getPlayer());
        }
    }

    static boolean isAfkCommand(String message) {
        if (message == null) {
            return false;
        }
        String trimmed = message.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        String label = trimmed.split("\\s+", 2)[0];
        int namespace = label.indexOf(':');
        if (namespace >= 0) {
            label = label.substring(namespace + 1);
        }
        return label.equals("afk");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        service.activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        service.activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            service.activity(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        service.activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        service.activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        service.activity(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent event) {
        service.activity(event.getPlayer());
    }

    /** Attacking is activity; being hit is not. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker) {
            service.activity(attacker);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastMoveSignal.remove(event.getPlayer().getUniqueId());
    }
}
