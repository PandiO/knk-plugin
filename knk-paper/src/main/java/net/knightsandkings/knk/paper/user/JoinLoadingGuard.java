package net.knightsandkings.knk.paper.user;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import net.knightsandkings.knk.paper.permissions.KnkPermissible;

/**
 * Puts a player into a temporary, harmless holding state for the window between
 * PlayerJoinEvent firing and their {@link PlayerUserData} finishing its async load
 * (see UserManager#onPlayerJoinAsync) - a precursor to the planned dedicated join-hub
 * world (game-world settings feature), implemented without one.
 * <p>
 * Deliberately NOT vanilla {@link GameMode#SPECTATOR}: spectator's noclip flight and
 * see-through-blocks rendering would let a loading player scout quest content, hidden
 * structures, or restricted areas they have no business seeing yet. Instead the player is
 * held in {@link GameMode#ADVENTURE} - normal camera, normal gravity/collision, no flight,
 * can't break/place blocks - made fully damage-immune, with combat/interaction/inventory
 * events cancelled by {@link net.knightsandkings.knk.paper.listeners.JoinLoadingRestrictionListener}
 * for as long as {@link #isLoading(UUID)} is true. Net effect: they can walk around and look
 * at the world exactly like any other player, but can't touch, damage, or be damaged by
 * anything, so there is nothing to exploit while their real permissions and balances are
 * still unknown. Players with {@code knk.mode.owner} are exempted, matching the existing
 * carve-out in PlayerListener#onJoin.
 * <p>
 * A repeating action-bar reminder keeps the loading player informed for the whole window
 * (rather than a single message that scrolls out of sight), and
 * {@link net.knightsandkings.knk.paper.listeners.JoinLoadingRestrictionListener} tells anyone
 * whose interaction with them got cancelled why nothing happened.
 */
public class JoinLoadingGuard {

    private static final int SAFETY_TIMEOUT_SECONDS = 15;
    private static final long ACTION_BAR_PERIOD_TICKS = 20L; // once a second

    private final Plugin plugin;
    private final KnkPermissible knkPermissible;
    private final Set<UUID> loadingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Boolean> previousInvulnerable = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> reminderTasks = new ConcurrentHashMap<>();

    public JoinLoadingGuard(Plugin plugin, KnkPermissible knkPermissible) {
        this.plugin = plugin;
        this.knkPermissible = knkPermissible;
    }

    /**
     * Called right after the player's normal join teleport/gamemode has been applied.
     * Holds the player until {@link #release(Player)} is called.
     */
    public void hold(Player player) {
        if (knkPermissible.hasPermission(player, "knk.mode.owner")) {
            return;
        }

        UUID uuid = player.getUniqueId();
        loadingPlayers.add(uuid);
        previousInvulnerable.put(uuid, player.isInvulnerable());

        player.setGameMode(GameMode.ADVENTURE);
        player.setInvulnerable(true);
        player.sendMessage(
            Component.text("Loading your account... you can walk around, and you'll be able to play in a moment.")
                .color(NamedTextColor.GRAY)
        );

        BukkitTask reminder = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            player.sendActionBar(
                Component.text("⏳ Loading your account...").color(NamedTextColor.YELLOW)
            );
        }, 0L, ACTION_BAR_PERIOD_TICKS);
        reminderTasks.put(uuid, reminder);
    }

    /**
     * Called once the player's data has finished loading (successfully or via fallback).
     * No-ops for players never held (e.g. owners).
     */
    public void release(Player player) {
        UUID uuid = player.getUniqueId();
        if (!loadingPlayers.remove(uuid)) {
            return;
        }

        stopReminder(uuid);

        Boolean wasInvulnerable = previousInvulnerable.remove(uuid);
        if (player.getGameMode() == GameMode.ADVENTURE) {
            // If something else already changed their gamemode, leave it alone.
            player.setGameMode(GameMode.SURVIVAL);
        }
        player.setInvulnerable(wasInvulnerable != null && wasInvulnerable);
        player.sendActionBar(Component.text("You're all set!").color(NamedTextColor.GREEN));
    }

    /**
     * Whether this player is currently in the join-loading hold - checked by
     * {@link net.knightsandkings.knk.paper.listeners.JoinLoadingRestrictionListener} to decide
     * whether to cancel an interaction/combat/inventory event.
     */
    public boolean isLoading(UUID uuid) {
        return loadingPlayers.contains(uuid);
    }

    /**
     * Drops any held state for a player who disconnected before their data finished loading.
     */
    public void forget(UUID uuid) {
        loadingPlayers.remove(uuid);
        previousInvulnerable.remove(uuid);
        stopReminder(uuid);
    }

    private void stopReminder(UUID uuid) {
        BukkitTask task = reminderTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * Hard cap (in seconds) on the async user-data fetch (see UserManager), so a hung API call
     * can't leave a player stuck in this holding state indefinitely.
     */
    public static int safetyTimeoutSeconds() {
        return SAFETY_TIMEOUT_SECONDS;
    }
}
