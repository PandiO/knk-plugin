package net.knightsandkings.knk.paper.listeners;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
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
import net.knightsandkings.knk.paper.currency.CurrencySettings;
import net.knightsandkings.knk.paper.currency.PlayerCurrencyService;
import net.knightsandkings.knk.paper.events.UserDataLoadedEvent;
import net.knightsandkings.knk.paper.onboarding.NewPlayerWindow;
import net.knightsandkings.knk.paper.onboarding.OnboardingMessages;
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
 * - Display welcome messages with account status: the first-join welcome for an account created at
 *   this join, "Welcome back" otherwise (KNG-128)
 * - Prompt for account linking if needed: in the first-join welcome, then on every later join until
 *   the player has a web account (KNG-129)
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
    /** The balance line in /balance's format (currency ledger); null if the currency service failed to start. */
    private final PlayerCurrencyService currencyService;
    /** Marked here when this join is the player's first (KNG-128, PandiO/knk-workspace#486). */
    private final NewPlayerWindow newPlayers;
    private final KnkConfig.OnboardingConfig onboarding;
    /** PandiO/knk-workspace#486: true once when the player's previous account was erased since their last join (and forgets it). */
    private volatile Predicate<UUID> takeErasedSince = uuid -> false;
    /** Asks the API for the first-join kits of (player, user id); PlayerListener#requestFirstJoinKits. */
    private volatile BiConsumer<UUID, Integer> firstJoinKits = (uuid, userId) -> { };

    /** The erasure mark ({@code ErasedPlayerMarks#take}) and the first-join kit request; none until set. */
    public void setFirstJoinHooks(Predicate<UUID> takeErasedSince, BiConsumer<UUID, Integer> firstJoinKits) {
        this.takeErasedSince = takeErasedSince != null ? takeErasedSince : uuid -> false;
        this.firstJoinKits = firstJoinKits != null ? firstJoinKits : (uuid, userId) -> { };
    }

    public UserAccountListener(
        Plugin plugin,
        UserManager userManager,
        JoinLoadingGuard joinLoadingGuard,
        KnkConfig.MessagesConfig messagesConfig,
        Logger logger,
        PlayerCurrencyService currencyService,
        NewPlayerWindow newPlayers,
        KnkConfig.OnboardingConfig onboarding
    ) {
        this.plugin = plugin;
        this.userManager = userManager;
        this.joinLoadingGuard = joinLoadingGuard;
        this.messagesConfig = messagesConfig;
        this.logger = logger;
        this.currencyService = currencyService;
        this.newPlayers = newPlayers;
        this.onboarding = onboarding != null ? onboarding : KnkConfig.OnboardingConfig.defaults();
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
        // Read now: whether the server had player data for them when they joined.
        boolean playedBefore = player.hasPlayedBefore();

        userManager.onPlayerJoinAsync(player).thenAccept(userData -> {
            // Release the hold as soon as the account is known; the welcome lines wait for the
            // fresh balance read below, which must not keep the player in the loading hold.
            Bukkit.getScheduler().runTask(plugin, () -> {
                // Player may have disconnected again before the fetch finished.
                Player online = Bukkit.getPlayer(uuid);
                if (online != null) {
                    // Before the welcome below and before UserDataLoadedEvent starts the join's discoveries.
                    decideFirstJoin(uuid, online.getName(), userData, playedBefore);
                    joinLoadingGuard.release(online);
                }
            });
            balanceLine(uuid, userData).thenAccept(balanceLine ->
                Bukkit.getScheduler().runTask(plugin, () -> sendJoinMessages(uuid, userData, balanceLine)));
        });
    }

    /**
     * The one first-join decision of a join (PandiO/knk-workspace#486), made once the join's sync knows the account,
     * never from the user cache at {@code PlayerJoinEvent} (an erasure empties it, and so does a website account that
     * has no UUID yet). A first join is an account the API created while this player connected, a player the server
     * has no player data for (KNG-214), or a player whose previous account was erased since their last join (the
     * erasure's mark, taken here whatever the cache holds). It marks the join for the first-join welcome and the quiet
     * discovery minutes ({@link NewPlayerWindow}) and asks the API for the first-join kits: all three follow from this
     * decision. Without an account (the API didn't answer) nothing is decided and the erasure mark waits for the next
     * join. Main thread; returns whether this is a first join.
     */
    boolean decideFirstJoin(UUID uuid, String name, PlayerUserData userData, boolean hasPlayedBefore) {
        if (userData == null || userData.userId() == null) {
            logger.info("First join: no account for " + name + " after the join's sync, not decided");
            return false;
        }
        // Both read on every join, so neither note outlives the join it was made for.
        boolean created = newPlayers != null && newPlayers.takeAccountCreated(uuid);
        boolean erasedSince = takeErasedSince.test(uuid);
        if (!NewPlayerWindow.isFirstJoin(created, hasPlayedBefore, erasedSince)) {
            return false;
        }
        if (newPlayers != null) {
            newPlayers.markFirstJoin(uuid);
        }
        logger.info("First join: " + name + " (user " + userData.userId() + ") "
            + (created ? "has a new account"
                : erasedSince ? "returns after their previous account was erased"
                : "joins for the first time with an existing account")
            + "; first-join welcome, quiet discovery minutes, asking the API for the first-join kits");
        try {
            firstJoinKits.accept(uuid, userData.userId());
        } catch (RuntimeException ex) {
            logger.warning("First-join kits for user " + userData.userId() + " not requested: " + ex.getMessage());
        }
        return true;
    }

    private void sendJoinMessages(UUID uuid, PlayerUserData userData, String balanceLine) {
        Player online = Bukkit.getPlayer(uuid);
        if (online == null) {
            return;
        }
        try {
            // hasEmailLinked reflects the API's account: true once the player registered on the website.
            boolean unlinked = !userData.hasEmailLinked() && userData.userId() != null;
            boolean firstJoin = newPlayers != null && newPlayers.takeFirstJoinWelcome(uuid);
            if (firstJoin) {
                sendFirstJoinWelcome(online, balanceLine, unlinked && !userData.hasDuplicateAccount());
            } else {
                sendWelcomeMessage(online, balanceLine);
            }

            // Check for duplicate account and prompt if needed
            if (userData.hasDuplicateAccount()) {
                sendDuplicateAccountPrompt(online, userData);
            } else if (unlinked && !firstJoin && onboarding.unlinkedReminderEnabled()) {
                // KNG-129: every later join until the player has a web account. The first join's welcome
                // already asks, and the duplicate prompt points to /account link itself.
                online.sendMessage(getPrefixComponent().append(linkPrompt(onboarding.unlinkedReminder())));
            }
        } catch (Exception ex) {
            logger.log(java.util.logging.Level.SEVERE,
                "Failed to display join messages for " + online.getName() + ": " + ex.getMessage(), ex);

            online.sendMessage(
                getPrefixComponent()
                    .append(Component.text("Welcome! Your account data could not be loaded. Please contact an admin if this persists.")
                        .color(NamedTextColor.YELLOW))
            );
        }

        // The account is known and the loading hold is off: features that need the user id at
        // join (e.g. domain discovery of the region they joined in) start here.
        Bukkit.getPluginManager().callEvent(new UserDataLoadedEvent(online, userData));
    }
    
    /**
     * Handle player quit - clear cached user data, and take the player out of the join-loading
     * hold before they are saved (this event fires before the save).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        userManager.clearCachedUser(player.getUniqueId());
        joinLoadingGuard.forget(player);
        logger.fine("Cleared cache for " + player.getName() + " on quit");
    }
    
    /**
     * The balance line, in {@code /balance}'s format ({@code messages.currency.balance-self}) and
     * read fresh from the API like {@code /balance}; the join lookup's numbers if the API doesn't
     * answer. Completes with null when the player has no account yet.
     */
    private CompletableFuture<String> balanceLine(UUID uuid, PlayerUserData userData) {
        if (userData == null || userData.userId() == null) {
            return CompletableFuture.completedFuture(null);
        }
        long coins = userData.coins() != null ? userData.coins() : 0;
        long gems = userData.gems() != null ? userData.gems() : 0;
        if (currencyService == null) {
            return CompletableFuture.completedFuture(PlayerCurrencyService.balanceSelfLine(CurrencySettings.defaults(), coins, gems));
        }
        return currencyService.joinBalanceLine(uuid, userData.userId(), coins, gems);
    }

    /**
     * Send welcome message with account balance info.
     */
    private void sendWelcomeMessage(Player player, String balanceLine) {
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
        if (balanceLine != null) {
            player.sendMessage(getPrefixComponent().append(LegacyComponentSerializer.legacySection().deserialize(balanceLine)));
        }
    }
    
    /**
     * The first-join welcome (KNG-128): the configured lines instead of "Welcome back", the balance, and
     * the link button when the account has no web login yet.
     */
    private void sendFirstJoinWelcome(Player player, String balanceLine, boolean promptLink) {
        OnboardingMessages.firstJoinWelcome(onboarding.firstJoinWelcome(), player.getName())
            .forEach(line -> player.sendMessage(getPrefixComponent().append(line)));
        if (balanceLine != null) {
            player.sendMessage(getPrefixComponent().append(LegacyComponentSerializer.legacySection().deserialize(balanceLine)));
        }
        if (promptLink) {
            player.sendMessage(getPrefixComponent().append(linkPrompt(onboarding.linkPrompt())));
        }
    }

    /** {@code text}, then the button that runs /account link (code with click-to-copy, register URL). */
    private Component linkPrompt(String text) {
        return OnboardingMessages.linkPrompt(text, onboarding.linkButton(), onboarding.linkButtonHover());
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
                .append(LegacyComponentSerializer.legacySection().deserialize(
                    ChatColor.translateAlternateColorCodes('&', messagesConfig.duplicateAccount()))
                    .colorIfAbsent(NamedTextColor.YELLOW))
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
}
