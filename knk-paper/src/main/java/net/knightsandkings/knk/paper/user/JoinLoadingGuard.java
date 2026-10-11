package net.knightsandkings.knk.paper.user;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

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
 * <p>
 * The hold must be undone on the live Player before Bukkit saves them: the ADVENTURE mode and
 * the invulnerable flag are written to their playerdata. {@link #release(Player)} undoes it once
 * their data loads, {@link #forget(Player)} when they quit first (PlayerQuitEvent fires before
 * the save), and {@link #releaseAll()} on plugin disable for everyone still held.
 * <p>
 * Leaving the hold always clears the invulnerable flag rather than restoring what it was on join.
 * Nothing in the plugin makes a player invulnerable on purpose besides this guard, so a player who
 * joins already invulnerable is left over from an earlier hold that leaked (before quit/disable
 * restored it) - trusting that {@code true} would keep them immune to all damage forever, with no
 * vanilla way to clear it ({@code /data} can't edit players). If a feature ever sets player
 * invulnerability deliberately, it must tell this guard so the hold can hand it back.
 */
public class JoinLoadingGuard {

    private static final int SAFETY_TIMEOUT_SECONDS = 15;
    private static final long ACTION_BAR_PERIOD_TICKS = 20L; // once a second

    private final Plugin plugin;
    private final KnkPermissible knkPermissible;
    /** The mode a player gets back when the hold ends: their world's Game Settings default (KNG-52). */
    private final Function<Player, GameMode> gameModeAfterHold;
    private final Set<UUID> loadingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BukkitTask> reminderTasks = new ConcurrentHashMap<>();

    public JoinLoadingGuard(Plugin plugin, KnkPermissible knkPermissible) {
        this(plugin, knkPermissible, player -> GameMode.SURVIVAL);
    }

    /**
     * @param gameModeAfterHold the mode to hand back when the hold ends (docs/specs/game-settings/DESIGN.md
     *                          §3.4); a null answer means SURVIVAL
     */
    public JoinLoadingGuard(Plugin plugin, KnkPermissible knkPermissible, Function<Player, GameMode> gameModeAfterHold) {
        this.plugin = plugin;
        this.knkPermissible = knkPermissible;
        this.gameModeAfterHold = gameModeAfterHold != null ? gameModeAfterHold : player -> GameMode.SURVIVAL;
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
        if (player.isInvulnerable()) {
            // See the class Javadoc: nothing else sets this, so it leaked from an earlier hold.
            plugin.getLogger().info("Clearing invulnerability left over from an interrupted join hold on "
                + player.getName() + " when the hold ends");
        }

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
        if (endHold(player)) {
            player.sendActionBar(Component.text("You're all set!").color(NamedTextColor.GREEN));
        }
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
     * Undoes the hold for a player who disconnects before their data finished loading. Called
     * from PlayerQuitEvent, which fires before the player is saved, so the ADVENTURE mode and
     * invulnerable flag never reach their playerdata. No-ops for players not held.
     */
    public void forget(Player player) {
        endHold(player);
    }

    /**
     * Undoes the hold for every online player still in it - for plugin disable (server stop or
     * reload), when their data load will never complete and they are about to be saved.
     */
    public void releaseAll() {
        for (UUID uuid : Set.copyOf(loadingPlayers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                endHold(player);
            } else {
                loadingPlayers.remove(uuid);
                stopReminder(uuid);
            }
        }
    }

    /** Takes the player out of the hold; false if they weren't in it. */
    private boolean endHold(Player player) {
        UUID uuid = player.getUniqueId();
        if (!loadingPlayers.remove(uuid)) {
            return false;
        }

        stopReminder(uuid);

        GameMode before = player.getGameMode();
        if (before == GameMode.ADVENTURE) {
            // If something else already changed their gamemode, leave it alone.
            GameMode mode = gameModeAfterHold.apply(player);
            GameMode handedBack = mode != null ? mode : GameMode.SURVIVAL;
            player.setGameMode(handedBack);
            plugin.getLogger().info("[KnK GameSettings] " + player.getName() + " left the loading hold in " + handedBack
                + " (world " + (player.getWorld() != null ? player.getWorld().getName() : "?") + ")");
            // KNG-52 smoke test step 4: a regular player ended up in SURVIVAL although the world's
            // default was ADVENTURE. Say so if anything changes the mode right after the hold.
            if (player.isOnline()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline() && player.getGameMode() != handedBack) {
                        plugin.getLogger().warning("[KnK GameSettings] " + player.getName() + "'s game mode changed from "
                            + handedBack + " to " + player.getGameMode() + " within 2 s after the loading hold");
                    }
                }, 40L);
            }
        } else {
            plugin.getLogger().info("[KnK GameSettings] " + player.getName() + " left the loading hold in " + before
                + ", set by something else during the hold - left as it is");
        }
        player.setInvulnerable(false);
        return true;
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
