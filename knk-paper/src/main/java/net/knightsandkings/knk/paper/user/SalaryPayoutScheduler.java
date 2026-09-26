package net.knightsandkings.knk.paper.user;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.UsersDataAccess;
import net.knightsandkings.knk.core.domain.users.SalaryPayoutResult;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.kyori.adventure.text.Component;

/**
 * Pays salary (vision §5.4, docs/specs/user-features/DESIGN.md §5) on join and then every hour
 * a player stays online. knk-web-api decides eligibility and amount - the title's hourly Salary
 * x global x personal x rank multipliers x hours since the last payout, only once at least an
 * hour has passed - so a join after an hour or more away pays the gap straight away, and an
 * online player is paid as each hour completes.
 *
 * <p>Before this, the join payout was the only trigger: a player who stayed online was never
 * paid until their next login.
 *
 * <p>A one-minute main-thread check calls the API only for players whose next eligible time
 * (taken from the last payout result) has passed, so an online player costs about one API call
 * an hour, not one a minute.
 */
public class SalaryPayoutScheduler implements Listener {
    private static final Logger LOGGER = Logger.getLogger(SalaryPayoutScheduler.class.getName());
    private static final long CHECK_INTERVAL_TICKS = 60L * 20L;
    /** After a failed call (API down, user unknown), wait this long before asking again. */
    static final Duration RETRY_DELAY = Duration.ofMinutes(5);
    /** Fallback when a result carries no nextEligibleAt; mirrors the API's minimum interval. */
    static final Duration PAYOUT_INTERVAL = Duration.ofHours(1);

    private final Plugin plugin;
    private final UsersCommandApi usersCommandApi;
    private final UserCache userCache;
    private final UsersDataAccess usersDataAccess;
    private final BiConsumer<Player, UserSummary> displayRefresher;
    private final Clock clock;

    /** Online players only; absent means "check now". */
    private final Map<UUID, Instant> nextCheckAt = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private BukkitTask task;

    public SalaryPayoutScheduler(
            Plugin plugin,
            UsersCommandApi usersCommandApi,
            UserCache userCache,
            UsersDataAccess usersDataAccess,
            BiConsumer<Player, UserSummary> displayRefresher) {
        this(plugin, usersCommandApi, userCache, usersDataAccess, displayRefresher, Clock.systemUTC());
    }

    SalaryPayoutScheduler(
            Plugin plugin,
            UsersCommandApi usersCommandApi,
            UserCache userCache,
            UsersDataAccess usersDataAccess,
            BiConsumer<Player, UserSummary> displayRefresher,
            Clock clock) {
        this.plugin = plugin;
        this.usersCommandApi = usersCommandApi;
        this.userCache = userCache;
        this.usersDataAccess = usersDataAccess;
        this.displayRefresher = displayRefresher;
        this.clock = clock;
    }

    public void start() {
        if (usersCommandApi == null) {
            LOGGER.warning("SalaryPayoutScheduler not started: no UsersCommandApi");
            return;
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::checkOnlinePlayers, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        nextCheckAt.clear();
    }

    /**
     * Join payout: covers the time since the last payout (offline gap included) as soon as an
     * hour or more has passed. A brand-new account may not be cached yet at this point; the
     * minute check then picks it up.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        nextCheckAt.remove(event.getPlayer().getUniqueId());
        requestPayout(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        nextCheckAt.remove(event.getPlayer().getUniqueId());
    }

    /** Main thread. */
    void checkOnlinePlayers() {
        Instant now = clock.instant();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (isDue(nextCheckAt.get(uuid), now)) {
                requestPayout(uuid);
            }
        }
    }

    static boolean isDue(Instant nextCheck, Instant now) {
        return nextCheck == null || !now.isBefore(nextCheck);
    }

    private void requestPayout(UUID uuid) {
        if (usersCommandApi == null || inFlight.contains(uuid)) {
            return;
        }
        UserSummary user = userCache.getByUuid(uuid).orElse(null);
        if (user == null || user.id() == null) {
            return;
        }
        int userId = user.id();
        inFlight.add(uuid);
        usersCommandApi.payOutSalaryById(userId).whenComplete((result, ex) -> {
            if (ex != null) {
                LOGGER.log(Level.WARNING, "Failed to pay out salary for user " + userId, ex);
            }
            runOnMainThread(() -> {
                inFlight.remove(uuid);
                handleResult(uuid, userId, result);
            });
        });
    }

    /** Main thread. {@code result} is null when the call failed. */
    private void handleResult(UUID uuid, int userId, SalaryPayoutResult result) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return; // Left while the call was running; onQuit already dropped their entry.
        }
        nextCheckAt.put(uuid, nextCheck(result, clock.instant()));
        if (result == null || !result.paid()) {
            return;
        }
        LOGGER.fine("Paid out " + result.amountPaid() + " salary coins to user " + userId);
        player.sendMessage(Component.text(payoutMessage(result)).color(ColorOptions.messageachievement));
        refreshDisplay(uuid);
    }

    static Instant nextCheck(SalaryPayoutResult result, Instant now) {
        if (result == null) {
            return now.plus(RETRY_DELAY);
        }
        if (result.nextEligibleAt() != null) {
            return result.nextEligibleAt().toInstant();
        }
        return now.plus(PAYOUT_INTERVAL);
    }

    static String payoutMessage(SalaryPayoutResult result) {
        String message = "You received " + result.amountPaid() + " coins in salary";
        // Anything noticeably over an hour is a gap payout (offline time, or a server restart).
        if (result.hoursCovered() >= 1.5) {
            message += " for the past " + String.format(Locale.ROOT, "%.1f", result.hoursCovered()) + " hours";
        }
        return message + ".";
    }

    /** Re-reads the balance into the user cache and redraws the scoreboard with it. */
    private void refreshDisplay(UUID uuid) {
        if (usersDataAccess == null) {
            return;
        }
        usersDataAccess.refreshAsync(uuid).whenComplete((fetch, ex) -> {
            UserSummary fresh = ex == null && fetch != null ? fetch.value().orElse(null) : null;
            if (fresh == null || displayRefresher == null) {
                return;
            }
            runOnMainThread(() -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    displayRefresher.accept(player, fresh);
                }
            });
        });
    }

    private void runOnMainThread(Runnable runnable) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }
}
