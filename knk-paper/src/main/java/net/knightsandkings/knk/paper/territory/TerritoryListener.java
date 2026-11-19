package net.knightsandkings.knk.paper.territory;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.PluginManager;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.knightsandkings.knk.core.territory.TerritorySettings;
import net.knightsandkings.knk.core.territory.TerritoryTracker;

/**
 * The territory lookup on every chunk crossing (per block column inside split chunks; territory DESIGN § 1,
 * PandiO/knk-workspace#689): moves, teleports, respawns and world changes go to the {@link TerritoryTracker}; a change of
 * area fires {@link PlayerTerritoryChangeEvent} and, with {@code production.territory.notify-players}, an action bar line.
 * Registered only with {@code production.enabled}. Moves within one block column return at once.
 */
public final class TerritoryListener implements Listener {

    private static final LegacyComponentSerializer AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    private final TerritoryTracker tracker;
    private final TerritorySettings settings;
    private final PluginManager events;

    public TerritoryListener(TerritoryTracker tracker, TerritorySettings settings, PluginManager events) {
        this.tracker = tracker;
        this.settings = settings;
        this.events = events;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        tracker.forget(player.getUniqueId());
        at(player, player.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()
                && from.getWorld() == to.getWorld())) {
            return;
        }
        at(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null) {
            at(event.getPlayer(), event.getTo());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        at(event.getPlayer(), event.getRespawnLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        at(event.getPlayer(), event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        tracker.forget(event.getPlayer().getUniqueId());
    }

    /** Looks the player's new column up and reports a change of area. */
    void at(Player player, Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        TerritoryTracker.Change change = tracker.moved(player.getUniqueId(), location.getWorld().getName(),
                location.getBlockX(), location.getBlockZ());
        if (change == null) {
            return;
        }
        events.callEvent(new PlayerTerritoryChangeEvent(player, change.from(), change.to(), change.cache()));
        if (settings.notifyPlayers()) {
            String line = settings.messageFor(change);
            if (line != null && !line.isBlank()) {
                player.sendActionBar(AMPERSAND.deserialize(line));
            }
        }
    }
}
