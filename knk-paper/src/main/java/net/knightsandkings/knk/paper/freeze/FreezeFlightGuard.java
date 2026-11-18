package net.knightsandkings.knk.paper.freeze;

import net.knightsandkings.knk.core.freeze.FreezeFlightLedger;
import net.knightsandkings.knk.core.freeze.FreezeFlightLedger.Source;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.UUID;

/**
 * Keeps frozen players from being kicked for flying (PandiO/knk-workspace#155): while a player is frozen (the freeze
 * enchantment or a staff /freeze) and held in place, also in mid-air, flight is allowed for that player only, so the
 * server's {@code allow-flight} can stay false. The player's own setting comes back when the last freeze ends, on quit
 * (before the player data is saved) and on plugin disable. A frozen player can't start flying with the grant: the
 * toggle is cancelled.
 * <p>
 * Calls may come from any thread; the player is changed on the main thread.
 */
public class FreezeFlightGuard implements Listener {
    private final Plugin plugin;
    private final FreezeFlightLedger ledger = new FreezeFlightLedger();

    public FreezeFlightGuard(Plugin plugin) {
        this.plugin = plugin;
    }

    /** A freeze of this player started. */
    public void frozen(UUID playerId, Source source) {
        onMainThread(() -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                return;
            }
            if (ledger.grant(playerId, source, player.getAllowFlight())) {
                player.setAllowFlight(true);
                player.setFlying(false);
            }
        });
    }

    /** A freeze of this player ended. */
    public void released(UUID playerId, Source source) {
        onMainThread(() -> ledger.release(playerId, source).ifPresent(own -> handBack(Bukkit.getPlayer(playerId), own)));
    }

    /** Hands every frozen player's own flight setting back (plugin disable). Main thread only. */
    public void releaseAll() {
        for (UUID playerId : ledger.players()) {
            ledger.releaseAll(playerId).ifPresent(own -> handBack(Bukkit.getPlayer(playerId), own));
        }
    }

    public boolean isGranted(UUID playerId) {
        return ledger.isGranted(playerId);
    }

    /** Before the player data is saved, so a frozen player never logs back in with flight allowed. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Optional<Boolean> own = ledger.releaseAll(player.getUniqueId());
        own.ifPresent(value -> handBack(player, value));
    }

    /** The grant only keeps the server from kicking a frozen player; it doesn't let them fly off. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (event.isFlying() && ledger.isGranted(player.getUniqueId()) && !fliesByGameMode(player)) {
            event.setCancelled(true);
        }
    }

    private static void handBack(Player player, boolean ownAllowFlight) {
        if (player == null) {
            return;
        }
        if (FreezeFlightLedger.shouldDisallowOnRelease(ownAllowFlight, player.getAllowFlight(), fliesByGameMode(player))) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    private static boolean fliesByGameMode(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
    }

    private void onMainThread(Runnable task) {
        if (Bukkit.getServer() == null || Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
