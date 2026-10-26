package net.knightsandkings.knk.paper.realtime;

import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import net.knightsandkings.knk.api.realtime.UserStateEvents;
import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.PermissionGroupsDataAccess;
import net.knightsandkings.knk.core.dataaccess.PermissionsDataAccess;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.offline.OfflineSecurityStore;
import net.knightsandkings.knk.paper.user.AdminFreezeManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The game server's handlers for the S2 user-state events (PandiO/knk-workspace#179, DESIGN §6.1). Each handler calls
 * the refresh paths the plugin already has (P3): the permission set's invalidate + preload that in-game changes use
 * (KNG-107), the user cache's re-read, the tab-list redraw (KNG-7), {@link AdminFreezeManager}, the mode service.
 * Handlers are idempotent, so an echo of a change this server made itself, or a duplicate after a resync, changes
 * nothing visible.
 * <ul>
 *   <li>{@code UserPermissionsChanged}: drops the user's effective set <b>and</b> their offline-store answers (#76),
 *       then for an online player reloads the set, re-reads the user, redraws the tab list and re-checks visibility,
 *       the "/" list, social spy and their owner/staff mode.</li>
 *   <li>{@code PermissionGroupChanged}: drops the group list; every online member of the group (by the cached
 *       effective groups, parents included) as above.</li>
 *   <li>{@code UserBalancesChanged}: the committed coins, gems and XP into the cached user; a changed XP re-reads the
 *       user (title, prestige) and redraws.</li>
 *   <li>{@code UserFrozenChanged} / {@code UserModeChanged}: applied now instead of on the next join.</li>
 *   <li>{@code UserChanged}: the cached user is re-read (online) or dropped.</li>
 *   <li>{@code PlayerNotification}: handed to the notification poller's delivery.</li>
 * </ul>
 * Threads (P6): parsing and cache invalidation run on the realtime thread; everything that touches a player runs on
 * {@code mainThread}.
 */
public final class UserStateSync {

    /** What touches Bukkit beyond a message: the tab list, visibility, commands, social spy, modes. Main thread. */
    public interface Display {
        /** Re-renders the player's tab-list team and footer from {@code fresh}. */
        void redraw(Player player, UserSummary fresh);

        /** After a permission change: who they may see, the "/" list, social spy, and whether they keep their mode. */
        void permissionsApplied(Player player);

        /** A mode set elsewhere (web app): applied when they hold its node; a no-op when they are in it already. */
        void applyMode(Player player, ActiveMode mode);
    }

    private final Executor mainThread;
    private final Function<UUID, Player> onlinePlayer;
    private final Supplier<Collection<? extends Player>> onlinePlayers;
    private final UserCache userCache;
    private final UsersDataAccess users;
    private final PermissionsDataAccess permissions;
    private final PermissionGroupsDataAccess groups;
    private final OfflineSecurityStore offline;
    private final AdminFreezeManager freeze;
    private final Display display;
    private final Consumer<PlayerNotification> notifications;
    private final Logger logger;

    /**
     * @param permissions   null without in-house permissions (nothing to drop)
     * @param groups        null when the group list isn't cached
     * @param offline       null without the offline store (KNG-58)
     * @param freeze        null when freezing isn't wired
     * @param notifications where pushed player notifications go (the poller's delivery); null ignores them
     */
    public UserStateSync(Executor mainThread, Function<UUID, Player> onlinePlayer,
                         Supplier<Collection<? extends Player>> onlinePlayers, UserCache userCache, UsersDataAccess users,
                         PermissionsDataAccess permissions, PermissionGroupsDataAccess groups, OfflineSecurityStore offline,
                         AdminFreezeManager freeze, Display display, Consumer<PlayerNotification> notifications,
                         Logger logger) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.onlinePlayer = Objects.requireNonNull(onlinePlayer, "onlinePlayer");
        this.onlinePlayers = Objects.requireNonNull(onlinePlayers, "onlinePlayers");
        this.userCache = Objects.requireNonNull(userCache, "userCache");
        this.users = Objects.requireNonNull(users, "users");
        this.permissions = permissions;
        this.groups = groups;
        this.offline = offline;
        this.freeze = freeze;
        this.display = Objects.requireNonNull(display, "display");
        this.notifications = notifications;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Registers every S2 handler (before the client starts: it listens for the registered types). */
    public void register(RealtimeDispatcher dispatcher) {
        Executor here = Runnable::run;
        dispatcher.register(UserStateEvents.USER_PERMISSIONS_CHANGED, 1, here, this::onPermissionsChanged);
        dispatcher.register(UserStateEvents.PERMISSION_GROUP_CHANGED, 1, here, this::onGroupChanged);
        dispatcher.register(UserStateEvents.USER_BALANCES_CHANGED, 1, here, this::onBalancesChanged);
        dispatcher.register(UserStateEvents.USER_FROZEN_CHANGED, 1, here, this::onFrozenChanged);
        dispatcher.register(UserStateEvents.USER_MODE_CHANGED, 1, here, this::onModeChanged);
        dispatcher.register(UserStateEvents.USER_CHANGED, 1, here, this::onUserChanged);
        dispatcher.register(UserStateEvents.PLAYER_NOTIFICATION, 1, here, this::onPlayerNotification);
    }

    // ---- handlers (realtime thread) ----

    void onPermissionsChanged(RealtimeEnvelope envelope) {
        UserStateEvents.PermissionsChanged event = UserStateEvents.PermissionsChanged.parse(envelope);
        if (event == null) {
            ignored(envelope);
            return;
        }
        permissionsChanged(event.userId(), event.uuid());
    }

    void onGroupChanged(RealtimeEnvelope envelope) {
        UserStateEvents.GroupChanged event = UserStateEvents.GroupChanged.parse(envelope);
        if (event == null) {
            ignored(envelope);
            return;
        }
        if (groups != null) {
            groups.invalidate();
        }
        if (UserStateEvents.GroupChanged.CREATED.equals(event.op())) {
            return; // a new group has no members yet
        }
        mainThread.execute(() -> {
            for (Player player : List.copyOf(onlinePlayers.get())) {
                UserSummary cached = userCache.getStale(player.getUniqueId()).orElse(null);
                if (cached == null || cached.id() == null) {
                    continue; // not loaded yet: their join loads the current set anyway
                }
                // An empty list means the API sent none (older API): treat everyone as a member rather than miss one.
                boolean member = cached.permissionGroups().isEmpty()
                    || cached.permissionGroups().stream().anyMatch(group -> group.id() == event.groupId());
                if (member) {
                    permissionsChanged(cached.id(), player.getUniqueId());
                }
            }
        });
    }

    void onBalancesChanged(RealtimeEnvelope envelope) {
        UserStateEvents.BalancesChanged event = UserStateEvents.BalancesChanged.parse(envelope);
        if (event == null) {
            ignored(envelope);
            return;
        }
        mainThread.execute(() -> {
            UserSummary before = userCache.updateBalances(event.uuid(), event.coins(), event.gems(), event.xp());
            if (before == null || before.experiencePoints() == event.xp()) {
                return; // not cached (read fresh on the next use), or only coins/gems: nothing on screen to redraw
            }
            // XP decides the title and prestige in the tab list footer: the API computes those, so re-read.
            if (onlinePlayer.apply(event.uuid()) != null) {
                refreshAndRedraw(event.uuid());
            }
        });
    }

    void onFrozenChanged(RealtimeEnvelope envelope) {
        UserStateEvents.FrozenChanged event = UserStateEvents.FrozenChanged.parse(envelope);
        if (event == null) {
            ignored(envelope);
            return;
        }
        mainThread.execute(() -> applyFrozen(event.uuid(), event.frozen(), event.reason()));
    }

    void onModeChanged(RealtimeEnvelope envelope) {
        UserStateEvents.ModeChanged event = UserStateEvents.ModeChanged.parse(envelope);
        ActiveMode mode = event == null ? null : parseMode(event.mode());
        if (mode == null) {
            ignored(envelope);
            return;
        }
        mainThread.execute(() -> {
            userCache.getStale(event.uuid()).ifPresent(cached -> userCache.put(cached.withActiveMode(mode)));
            if (offline != null) {
                offline.setActiveMode(event.uuid(), mode);
            }
            Player player = onlinePlayer.apply(event.uuid());
            if (player != null) {
                display.applyMode(player, mode);
            }
        });
    }

    void onUserChanged(RealtimeEnvelope envelope) {
        UserStateEvents.UserChanged event = UserStateEvents.UserChanged.parse(envelope);
        if (event == null) {
            ignored(envelope);
            return;
        }
        mainThread.execute(() -> {
            if (onlinePlayer.apply(event.uuid()) == null) {
                users.invalidate(event.uuid());
            } else {
                refreshAndRedraw(event.uuid());
            }
        });
    }

    void onPlayerNotification(RealtimeEnvelope envelope) {
        PlayerNotification notification = UserStateEvents.parseNotification(envelope);
        if (notification == null) {
            ignored(envelope);
            return;
        }
        if (notifications != null) {
            notifications.accept(notification);
        }
    }

    // ---- resync (main thread) ----

    /**
     * Resync step after a (re)connect or a gap (DESIGN §4.4): events may have been missed, so every online player's
     * offline answers are dropped, the user is re-read and redrawn, and freeze, mode and permission-dependent state
     * are applied from the fresh record. (The permission sets themselves are dropped by the cache refresh hooks.)
     */
    public void resyncOnlinePlayers() {
        for (Player player : List.copyOf(onlinePlayers.get())) {
            UUID uuid = player.getUniqueId();
            UserSummary cached = userCache.getStale(uuid).orElse(null);
            if (cached != null && cached.id() != null && offline != null) {
                offline.forgetPermissionAnswers(cached.id());
            }
            refresh(uuid).thenAccept(fresh -> mainThread.execute(() -> {
                Player online = onlinePlayer.apply(uuid);
                if (fresh == null || online == null) {
                    return;
                }
                display.redraw(online, fresh);
                applyFrozen(uuid, fresh.isFrozen(), fresh.frozenReason());
                display.applyMode(online, fresh.activeMode());
                display.permissionsApplied(online);
            }));
        }
    }

    // ---- shared ----

    private void permissionsChanged(int userId, UUID uuid) {
        if (offline != null) {
            offline.forgetPermissionAnswers(userId);
        }
        if (permissions != null) {
            permissions.invalidateEffective(userId);
        }
        mainThread.execute(() -> {
            if (onlinePlayer.apply(uuid) == null) {
                return; // offline: the set loads again at their next join
            }
            CompletableFuture<Void> reload = permissions == null ? CompletableFuture.completedFuture(null)
                : permissions.preload(userId).exceptionally(ex -> null);
            reload.thenCompose(ignored -> refresh(uuid)).thenAccept(fresh -> mainThread.execute(() -> {
                Player player = onlinePlayer.apply(uuid);
                if (player == null) {
                    return;
                }
                if (fresh != null) {
                    display.redraw(player, fresh);
                }
                display.permissionsApplied(player);
            }));
        });
    }

    /** Main thread. Idempotent: only a real change freezes/unfreezes and tells the player. */
    private void applyFrozen(UUID uuid, boolean frozen, String reason) {
        String why = reason != null ? reason : "";
        userCache.getStale(uuid).ifPresent(cached -> userCache.put(cached.withFrozen(frozen, frozen ? why : null)));
        Player player = onlinePlayer.apply(uuid);
        if (player == null || freeze == null) {
            if (offline != null) {
                offline.setFrozen(uuid, frozen, frozen ? why : null);
            }
            return; // AdminFreezeManager re-reads the freeze at their next join
        }
        boolean wasFrozen = freeze.isFrozen(uuid);
        if (frozen) {
            freeze.freeze(uuid, why);
            if (!wasFrozen) {
                player.sendMessage(ChatColor.RED + "You have been frozen: " + why);
            }
        } else if (wasFrozen) {
            freeze.unfreeze(uuid);
            player.sendMessage(ChatColor.GREEN + "You have been unfrozen.");
        } else if (offline != null) {
            offline.setFrozen(uuid, false, null);
        }
    }

    private void refreshAndRedraw(UUID uuid) {
        refresh(uuid).thenAccept(fresh -> mainThread.execute(() -> {
            Player player = onlinePlayer.apply(uuid);
            if (player != null && fresh != null) {
                display.redraw(player, fresh);
            }
        }));
    }

    /** Re-reads the user from the API into the cache; null when that failed (the change already happened). */
    private CompletableFuture<UserSummary> refresh(UUID uuid) {
        return users.refreshAsync(uuid)
            .handle((result, ex) -> ex == null && result != null ? result.value().orElse(null) : null);
    }

    static ActiveMode parseMode(String mode) {
        if (mode == null) {
            return null;
        }
        try {
            return ActiveMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void ignored(RealtimeEnvelope envelope) {
        logger.warning("Realtime: " + envelope.type() + " (seq " + envelope.seq() + ") has a payload this plugin can't use; ignored");
    }
}
