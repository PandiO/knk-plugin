package net.knightsandkings.knk.paper.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * a promotion/demotion from an XP change made through the web admin's player profile page, and a
 * rank change made outside the plugin (web app, or a temporary rank expiring), which re-reads the
 * player so chat and the tab list show the new rank within one poll. Without this, that path only ever showed a banner in the browser: the API returned the
 * TitleChangeResult to the web app and the server never heard about it, so the online player got
 * the rewards but none of PromotionEffects' sound/particles/message. (/knk user ... xp worked
 * because the plugin was the caller and showed the effects straight from the response.)
 *
 * Polls GET /api/PlayerNotifications/pending, shows each notification whose player is online on
 * the main thread, then acknowledges the shown ones. Notifications for offline players are left
 * in the queue, so they show on the player's next join (the API expires them after 24h). Skips
 * the HTTP call entirely while nobody is online.
 *
 * TODO: like HeadlessWorldTaskPoller, replace with an API push (e.g. SignalR) once one exists.
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

    private volatile boolean running;
    private BukkitTask task;
    // Set once UserAdminService exists (it's built after this poller in KnKPlugin).
    private volatile Consumer<Player> rankChangedHandler;

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
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            scheduleNextPoll();
            return;
        }
        notificationsApi.listPending()
            .thenAccept(pending -> {
                if (pending.isEmpty()) {
                    scheduleNextPoll();
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> deliver(pending));
            })
            .exceptionally(ex -> {
                LOGGER.fine("PlayerNotificationPoller failed to list pending notifications: " + ex.getMessage());
                scheduleNextPoll();
                return null;
            });
    }

    /** Main thread. Schedules the next poll once the acknowledgement settles. */
    private void deliver(List<PlayerNotification> pending) {
        List<Long> toAcknowledge = new ArrayList<>();
        for (PlayerNotification notification : pending) {
            if (shownIds.contains(notification.id())) {
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
            scheduleNextPoll();
            return;
        }
        notificationsApi.acknowledge(toAcknowledge)
            .thenRun(() -> {
                toAcknowledge.forEach(shownIds::remove);
                scheduleNextPoll();
            })
            .exceptionally(ex -> {
                LOGGER.warning("PlayerNotificationPoller failed to acknowledge " + toAcknowledge + ": " + ex.getMessage());
                scheduleNextPoll();
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
