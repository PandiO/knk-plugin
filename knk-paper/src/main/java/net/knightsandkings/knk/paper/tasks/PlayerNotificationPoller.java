package net.knightsandkings.knk.paper.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import net.knightsandkings.knk.core.ports.api.PlayerNotificationsApi;
import net.knightsandkings.knk.paper.commands.support.PromotionEffects;

/**
 * Delivers in-game moments the web API queued for writes the plugin didn't make itself -
 * (currency Phase 3: also "you received N coins from X" after a /pay, on the recipient's next
 * join when they were offline; Phase 5: currency anomaly alerts for online staff) -
 * a promotion/demotion from an XP change made through the web admin's player profile page, and a
 * rank change made outside the plugin (web app, or a temporary rank expiring), which re-reads the
 * player so chat and the tab list show the new rank within one poll, and a domain discovery reset,
 * which re-syncs the player's discovery tracking so the reset place counts again right away, and a new
 * Minecraft-to-web account link (invitations, KNG-214), which tells the player the masked address.
 * Without this, that path only ever showed a banner in the browser: the API returned the
 * TitleChangeResult to the web app and the server never heard about it, so the online player got
 * the rewards but none of PromotionEffects' sound/particles/message. (/knk user ... xp worked
 * because the plugin was the caller and showed the effects straight from the response.)
 *
 * Polls GET /api/PlayerNotifications/pending, shows each notification whose player is online on
 * the main thread, then acknowledges the shown ones. Notifications for offline players are left
 * in the queue, so they show on the player's next join (the API expires them after 24h). Skips
 * the HTTP call entirely while nobody is online.
 *
 * <p>
 * Realtime S2 (PandiO/knk-workspace#179): while the realtime hub is connected the API pushes each notification
 * ({@link #deliverPushed}) and this poll makes no HTTP call; it drains the queue once on every (re)connect
 * ({@link #drainNow}) and polls as before while the hub is down or the channel is off. Shown notifications are
 * acknowledged over REST either way.
 */
public class PlayerNotificationPoller {
    private static final Logger LOGGER = Logger.getLogger(PlayerNotificationPoller.class.getName());
    private static final long DEFAULT_INTERVAL_SECONDS = 2L;

    private final PlayerNotificationsApi notificationsApi;
    private final Plugin plugin;
    private final long intervalTicks;
    // Shown but not yet confirmed acknowledged - guards against showing the same notification
    // twice if an acknowledge call fails and the next poll still lists it; the id is re-sent
    // for acknowledgement on that poll instead.
    private final Set<Long> shownIds = ConcurrentHashMap.newKeySet();
    // Realtime S2: recently acknowledged ids, so a push and a drain (or poll) that overlap never show one twice.
    private static final int ACKNOWLEDGED_MEMORY = 512;
    private final java.util.Map<Long, Boolean> acknowledgedIds = java.util.Collections.synchronizedMap(
        new java.util.LinkedHashMap<>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<Long, Boolean> eldest) {
                return size() > ACKNOWLEDGED_MEMORY;
            }
        });

    private volatile boolean running;
    private BukkitTask task;
    // Realtime S2: true while the hub delivers notifications; the poll loop then skips its HTTP call.
    private volatile java.util.function.BooleanSupplier hubConnected = () -> false;
    // Set once UserAdminService exists (it's built after this poller in KnKPlugin).
    private volatile Consumer<Player> rankChangedHandler;
    // Set once lootboxes are initialized (Phase 5 token items the API issued itself).
    private volatile Consumer<Player> lootboxTokensHandler;
    // Set once the currency commands exist (currency Phase 3).
    private volatile BiConsumer<Player, PlayerNotification> paymentReceivedHandler;
    // Currency anomaly alerts for online staff (currency Phase 5); not addressed to one player.
    private volatile Consumer<PlayerNotification> currencyAlertHandler;
    // Other notifications for the game server itself (userId 0), by type: e.g. LootboxWorldChanged.
    private final java.util.Map<String, Consumer<PlayerNotification>> serverHandlers = new ConcurrentHashMap<>();
    // A discovery reset made outside the plugin's own command (domain discovery, KNG-20).
    private volatile Consumer<Player> discoveryResetHandler;
    // A new Minecraft-to-web link (invitations, KNG-214): which web account now holds this player.
    private volatile BiConsumer<Player, PlayerNotification> accountLinkedHandler;

    public PlayerNotificationPoller(PlayerNotificationsApi notificationsApi, Plugin plugin) {
        this(notificationsApi, plugin,
            plugin.getConfig().getLong("player-notifications.poll-interval-seconds", DEFAULT_INTERVAL_SECONDS));
    }

    public PlayerNotificationPoller(PlayerNotificationsApi notificationsApi, Plugin plugin, long intervalSeconds) {
        this.notificationsApi = notificationsApi;
        this.plugin = plugin;
        this.intervalTicks = Math.max(1L, intervalSeconds) * 20L;
    }

    /** What to do for a {@link PlayerNotification#TYPE_RANK_CHANGED} whose player is online. */
    public void setRankChangedHandler(Consumer<Player> handler) {
        this.rankChangedHandler = handler;
    }

    /** What to do for a {@link PlayerNotification#TYPE_LOOTBOX_TOKENS_ISSUED} whose player is online. */
    public void setLootboxTokensHandler(Consumer<Player> handler) {
        this.lootboxTokensHandler = handler;
    }

    /** What to do for a {@link PlayerNotification#TYPE_PAYMENT_RECEIVED} whose player is online. */
    public void setPaymentReceivedHandler(BiConsumer<Player, PlayerNotification> handler) {
        this.paymentReceivedHandler = handler;
    }

    /**
     * What to do for a {@link PlayerNotification#TYPE_DISCOVERY_RESET} whose player is online: re-sync
     * their discovery tracking. An offline player's stays queued and is acknowledged on their join
     * (harmless: the known set is loaded fresh then anyway).
     */
    public void setDiscoveryResetHandler(Consumer<Player> handler) {
        this.discoveryResetHandler = handler;
    }

    /**
     * What to do for a {@link PlayerNotification#TYPE_ACCOUNT_LINKED} whose player is online. An offline player's stays
     * queued and shows on their next join (the API keeps it 24h).
     */
    public void setAccountLinkedHandler(BiConsumer<Player, PlayerNotification> handler) {
        this.accountLinkedHandler = handler;
    }

    /**
     * What to do for a {@link PlayerNotification#TYPE_CURRENCY_ALERT}: it is for every online staff
     * member with the alerts node, not one player, so it is handed over as soon as anyone is online.
     * Without a handler it stays queued (the API expires it after 24h).
     */
    public void setCurrencyAlertHandler(Consumer<PlayerNotification> handler) {
        this.currencyAlertHandler = handler;
    }

    /**
     * What to do for a notification of {@code type} addressed to the game server rather than a player (userId 0,
     * e.g. {@link PlayerNotification#TYPE_LOOTBOX_WORLD_CHANGED}). Handed over as soon as anyone is online; without a
     * handler it stays queued.
     */
    public void setServerNotificationHandler(String type, Consumer<PlayerNotification> handler) {
        if (handler == null) {
            serverHandlers.remove(type);
        } else {
            serverHandlers.put(type, handler);
        }
    }

    /** While this says true, the poll loop makes no HTTP call (the API pushes instead). */
    public void setHubConnected(java.util.function.BooleanSupplier hubConnected) {
        this.hubConnected = hubConnected != null ? hubConnected : () -> false;
    }

    /**
     * One poll right away, outside the loop (realtime resync after a (re)connect): picks up what was queued while the
     * hub was down, and anything a lost push left pending.
     */
    public void drainNow() {
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        notificationsApi.listPending()
            .thenAccept(pending -> {
                if (!pending.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> deliver(pending, () -> { }));
                }
            })
            .exceptionally(ex -> {
                LOGGER.fine("PlayerNotificationPoller drain failed: " + ex.getMessage());
                return null;
            });
    }

    /**
     * A notification the API pushed over the realtime hub (any thread). Shown on the main thread like a polled one
     * and acknowledged over REST; while nobody is online it is left in the API's queue (the next join or drain shows
     * it), as the poll does.
     */
    public void deliverPushed(PlayerNotification notification) {
        if (notification == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!Bukkit.getOnlinePlayers().isEmpty()) {
                deliver(List.of(notification), () -> { });
            }
        });
    }

    public void start() {
        running = true;
        scheduleNextPoll();
        LOGGER.info("PlayerNotificationPoller started (interval=" + (intervalTicks / 20L) + "s)");
    }

    public void stop() {
        running = false;
        if (task != null) {
            task.cancel();
        }
    }

    private void scheduleNextPoll() {
        if (!running) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, this::pollOnce, intervalTicks);
    }

    private void pollOnce() {
        if (Bukkit.getOnlinePlayers().isEmpty() || hubConnected.getAsBoolean()) {
            scheduleNextPoll();
            return;
        }
        notificationsApi.listPending()
            .thenAccept(pending -> {
                if (pending.isEmpty()) {
                    scheduleNextPoll();
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> deliver(pending, this::scheduleNextPoll));
            })
            .exceptionally(ex -> {
                LOGGER.fine("PlayerNotificationPoller failed to list pending notifications: " + ex.getMessage());
                scheduleNextPoll();
                return null;
            });
    }

    /** Main thread. Runs {@code afterAcknowledged} (the poll loop: schedule the next poll) once the acknowledgement settles. */
    private void deliver(List<PlayerNotification> pending, Runnable afterAcknowledged) {
        List<Long> toAcknowledge = new ArrayList<>();
        for (PlayerNotification notification : pending) {
            if (acknowledgedIds.containsKey(notification.id())) {
                continue; // shown and acknowledged already (pushed, then listed by an overlapping poll)
            }
            if (shownIds.contains(notification.id())) {
                toAcknowledge.add(notification.id());
                continue;
            }
            if (PlayerNotification.TYPE_CURRENCY_ALERT.equals(notification.type())) {
                Consumer<PlayerNotification> handler = currencyAlertHandler;
                if (handler == null) {
                    continue;
                }
                try {
                    handler.accept(notification);
                } catch (RuntimeException e) {
                    LOGGER.warning("Failed to show currency alert notification " + notification.id() + ": " + e.getMessage());
                }
                shownIds.add(notification.id());
                toAcknowledge.add(notification.id());
                continue;
            }
            Consumer<PlayerNotification> serverHandler = serverHandlers.get(notification.type());
            if (serverHandler != null) {
                try {
                    serverHandler.accept(notification);
                } catch (RuntimeException e) {
                    LOGGER.warning("Failed to apply server notification " + notification.id() + " (" + notification.type() + "): " + e.getMessage());
                }
                shownIds.add(notification.id());
                toAcknowledge.add(notification.id());
                continue;
            }
            Player player = findOnlinePlayer(notification);
            if (player == null) {
                continue; // stays queued for their next join
            }
            try {
                if (PlayerNotification.TYPE_TITLE_CHANGED.equals(notification.type())) {
                    PromotionEffects.show(player, notification.titleChange());
                } else if (PlayerNotification.TYPE_RANK_CHANGED.equals(notification.type()) && rankChangedHandler != null) {
                    rankChangedHandler.accept(player);
                } else if (PlayerNotification.TYPE_LOOTBOX_TOKENS_ISSUED.equals(notification.type()) && lootboxTokensHandler != null) {
                    lootboxTokensHandler.accept(player);
                } else if (PlayerNotification.TYPE_PAYMENT_RECEIVED.equals(notification.type()) && paymentReceivedHandler != null) {
                    paymentReceivedHandler.accept(player, notification);
                } else if (PlayerNotification.TYPE_DISCOVERY_RESET.equals(notification.type()) && discoveryResetHandler != null) {
                    discoveryResetHandler.accept(player);
                } else if (PlayerNotification.TYPE_ACCOUNT_LINKED.equals(notification.type()) && accountLinkedHandler != null) {
                    accountLinkedHandler.accept(player, notification);
                }
            } catch (RuntimeException e) {
                // Acknowledged anyway: retrying a notification that throws would only repeat
                // whatever part of it did get shown, and must not stop the poll loop.
                LOGGER.warning("Failed to show player notification " + notification.id() + ": " + e.getMessage());
            }
            shownIds.add(notification.id());
            toAcknowledge.add(notification.id());
        }

        if (toAcknowledge.isEmpty()) {
            afterAcknowledged.run();
            return;
        }
        notificationsApi.acknowledge(toAcknowledge)
            .thenRun(() -> {
                toAcknowledge.forEach(id -> {
                    acknowledgedIds.put(id, Boolean.TRUE);
                    shownIds.remove(id);
                });
                afterAcknowledged.run();
            })
            .exceptionally(ex -> {
                LOGGER.warning("PlayerNotificationPoller failed to acknowledge " + toAcknowledge + ": " + ex.getMessage());
                afterAcknowledged.run();
                return null;
            });
    }

    private static Player findOnlinePlayer(PlayerNotification notification) {
        if (notification.uuid() != null && !notification.uuid().isBlank()) {
            try {
                return Bukkit.getPlayer(UUID.fromString(notification.uuid()));
            } catch (IllegalArgumentException ignored) {
                // fall through to the username lookup
            }
        }
        return notification.username() != null ? Bukkit.getPlayerExact(notification.username()) : null;
    }
}
