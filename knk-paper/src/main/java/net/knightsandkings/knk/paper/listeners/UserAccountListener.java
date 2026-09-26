package net.knightsandkings.knk.paper.listeners;

import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.paper.config.KnkConfig;
import net.knightsandkings.knk.paper.events.UserDataLoadedEvent;
import net.knightsandkings.knk.paper.user.JoinLoadingGuard;
import net.knightsandkings.knk.paper.user.PlayerUserData;
import net.knightsandkings.knk.paper.user.UserManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Listener for player join/quit events related to account management.
 *
 * Responsibilities:
 * - Sync user data on join via UserManager
 * - Display welcome messages with account status
 * - Prompt for account linking if needed
 * - Clear cache on player quit
 *
 * Priority: HIGH
 * - Runs early in join sequence to ensure user data is available
 * - Must complete before other plugins that depend on user state
 *
 * The user-data fetch itself runs asynchronously (see UserManager#onPlayerJoinAsync) so
 * that the PlayerJoinEvent handler - which always fires on the main thread - never blocks
 * the player's join waiting on the web API. Until the fetch completes, getCachedUser()
 * returns null for this player; call sites already treat that as "not ready yet" (e.g.
 * AccountCommand, GateCommand). For everything else - anything that could touch coins,
 * gems, XP, items, or restricted areas before we actually know who this player is -
 * {@link JoinLoadingGuard} holds the player in a damage-immune, non-interactive Adventure
 * mode for the duration of the fetch (see its Javadoc for why not Spectator), a lightweight
 * stand-in for the planned dedicated join-hub world (game-world settings feature).
 */
public class UserAccountListener implements Listener {
    private final Plugin plugin;
    private final UserManager userManager;
    private final JoinLoadingGuard joinLoadingGuard;
    private final KnkConfig.MessagesConfig messagesConfig;
    private final Logger logger;

    public UserAccountListener(
        Plugin plugin,
        UserManager userManager,
        JoinLoadingGuard joinLoadingGuard,
        KnkConfig.MessagesConfig messagesConfig,
        Logger logger
    ) {
        this.plugin = plugin;
        this.userManager = userManager;
        this.joinLoadingGuard = joinLoadingGuard;
        this.messagesConfig = messagesConfig;
        this.logger = logger;
    }
    
    /**
     * Convert prefix string with legacy color codes to Adventure Component.
     * Translates &c, &4, etc. to proper Adventure TextColor objects.
     */
    private Component getPrefixComponent() {
        String legacyFormatted = ChatColor.translateAlternateColorCodes('&', messagesConfig.prefix());
        return LegacyComponentSerializer.legacySection().deserialize(legacyFormatted);
    }
    
    /**
     * Handle player join - sync user data and display account status.
     *
     * Priority: HIGH to ensure the fetch is kicked off early in the join sequence.
     *
     * The API round trip runs asynchronously so the player is not held on "Joining game"
     * while it completes - the join itself is never blocked. Everything that touches the
     * Player object (messages, prompts) is hopped back onto the main thread once the data
     * arrives, since Bukkit API calls aren't safe off it.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        logger.info("Player " + player.getName() + " joined, syncing account data...");
        joinLoadingGuard.hold(player);

        userManager.onPlayerJoinAsync(player).thenAccept(userData ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Player may have disconnected again before the fetch finished.
                Player online = Bukkit.getPlayer(uuid);
                if (online == null) {
                    return;
                }

                joinLoadingGuard.release(online);

                try {
                    sendWelcomeMessage(online, userData);

                    // Check for duplicate account and prompt if needed
                    if (userData.hasDuplicateAccount()) {
                        sendDuplicateAccountPrompt(online, userData);
                    }

                    // Check for minecraft-only account and suggest linking
                    // hasEmailLinked now reflects isFullAccount from API (true = has email + password)
                    if (!userData.hasEmailLinked() && userData.userId() != null) {
                        sendAccountLinkSuggestion(online);
                    }
                } catch (Exception ex) {
                    logger.severe("Failed to display join messages for " + online.getName() + ": " + ex.getMessage());
                    ex.printStackTrace();

                    online.sendMessage(
                        getPrefixComponent()
                            .append(Component.text("Welcome! Your account data could not be loaded. Please contact an admin if this persists.")
                                .color(NamedTextColor.YELLOW))
                    );
                }

                // The account is known and the loading hold is off: features that need the user
                // id at join (e.g. domain discovery of the region they joined in) start here.
                Bukkit.getPluginManager().callEvent(new UserDataLoadedEvent(online, userData));
            })
        );
    }
    
    /**
     * Handle player quit - clear cached user data.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        userManager.clearCachedUser(player.getUniqueId());
        joinLoadingGuard.forget(player.getUniqueId());
        logger.fine("Cleared cache for " + player.getName() + " on quit");
    }
    
    /**
     * Send welcome message with account balance info.
     */
    private void sendWelcomeMessage(Player player, PlayerUserData userData) {
        Component welcomeMsg = getPrefixComponent()
            .append(Component.text("Welcome back, ")
                .color(NamedTextColor.GREEN))
            .append(Component.text(player.getName())
                .color(NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD))
            .append(Component.text("!")
                .color(NamedTextColor.GREEN));
        
        player.sendMessage(welcomeMsg);
        
        // Show balance if available
        if (userData.userId() != null) {
            Component balanceMsg = getPrefixComponent()
                .append(Component.text("Balance: ")
                    .color(NamedTextColor.GRAY))
                .append(Component.text(userData.coins() + " coins")
                    .color(NamedTextColor.GOLD))
                .append(Component.text(", ")
                    .color(NamedTextColor.GRAY))
                .append(Component.text(userData.gems() + " gems")
                    .color(NamedTextColor.AQUA))
                .append(Component.text(", ")
                    .color(NamedTextColor.GRAY))
                .append(Component.text(userData.experiencePoints() + " XP")
                    .color(NamedTextColor.GREEN));
            
            player.sendMessage(balanceMsg);
        }
    }
    
    /**
     * Send prompt for duplicate account resolution.
     */
    private void sendDuplicateAccountPrompt(Player player, PlayerUserData userData) {
        player.sendMessage(Component.empty());
        player.sendMessage(
            getPrefixComponent()
                .append(Component.text("⚠ DUPLICATE ACCOUNT DETECTED")
                    .color(NamedTextColor.RED)
                    .decorate(TextDecoration.BOLD))
        );
        
        player.sendMessage(
            getPrefixComponent()
                .append(Component.text(messagesConfig.duplicateAccount())
                    .color(NamedTextColor.YELLOW))
        );
        
        player.sendMessage(
            getPrefixComponent()
                .append(Component.text("Use ")
                    .color(NamedTextColor.GRAY))
                .append(Component.text("/account link")
                    .color(NamedTextColor.GOLD)
                    .decorate(TextDecoration.UNDERLINED))
                .append(Component.text(" to resolve your accounts.")
                    .color(NamedTextColor.GRAY))
        );
        
        player.sendMessage(Component.empty());
    }
    
    /**
     * Send suggestion to link account with email for web app access.
     */
    private void sendAccountLinkSuggestion(Player player) {
        player.sendMessage(Component.empty());
        
        player.sendMessage(
            getPrefixComponent()
                .append(Component.text("💡 TIP: ")
                    .color(NamedTextColor.AQUA)
                    .decorate(TextDecoration.BOLD))
                .append(Component.text("Link your account to access the web app!")
                    .color(NamedTextColor.GRAY))
        );
        
        player.sendMessage(
            getPrefixComponent()
                .append(Component.text("Use ")
                    .color(NamedTextColor.GRAY))
                .append(Component.text("/account link")
                    .color(NamedTextColor.GOLD)
                    .decorate(TextDecoration.UNDERLINED))
                .append(Component.text(" with a code from the web app to get started.")
                    .color(NamedTextColor.GRAY))
        );
        
        player.sendMessage(Component.empty());
    }
}
