package net.knightsandkings.knk.paper.modes;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.paper.permissions.KnkPermissible;
import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.kyori.adventure.text.Component;

/**
 * Owner/staff mode (docs/specs/user-features/DESIGN.md §6.1, IMPLEMENTATION_PLAN.md §3) - the v3
 * rebuild of v1's /ownermode and /staffmode vanish-like toggle.
 * <p>
 * v1 semantics kept: entering a mode hides the player from every online player who can't see
 * vanished players, and shows an action-bar confirmation; leaving it shows them to everyone
 * again. v1 hid from "anyone lacking k&amp;k.staff"; here a viewer can see vanished players if
 * they hold {@code knk.mode.staff} or {@code knk.mode.owner} (via {@link KnkPermissible}, so ops
 * always can) - an owner-only grant without the staff node would otherwise be unable to see
 * other vanished staff/owners.
 * <p>
 * Changed from v1: the mode is persisted on the user (knk-web-api {@code User.ActiveMode}) and
 * restored on login by {@link ModeListener}, instead of living in in-memory maps that reset
 * everyone to visible on every restart. The two modes are mutually exclusive (one
 * {@link ActiveMode} per player).
 * <p>
 * Threading: every method here that touches Bukkit visibility must run on the main thread.
 * Permission checks for viewers go through {@link KnkPermissible#hasPermissionAsync} (a real
 * resolution, not a cache-only snapshot that fails closed on a cold cache); visibility is always
 * hidden first, then revealed to viewers once their check resolves, so a vanished player is
 * never briefly visible to someone who shouldn't see them.
 */
public class ModeService {

    public static final String OWNER_NODE = "knk.mode.owner";
    public static final String STAFF_NODE = "knk.mode.staff";

    private static final Logger LOGGER = Logger.getLogger(ModeService.class.getName());

    private final Plugin plugin;
    private final KnkPermissible knkPermissible;
    private final UserCache userCache;
    private final UsersCommandApi usersCommandApi;

    // In-session mode per online player. The persisted value (UserCache/API) can differ from
    // this while an "onquit" change is pending - see ModeCommand.
    private final Map<UUID, ActiveMode> activeModes = new ConcurrentHashMap<>();

    public ModeService(Plugin plugin, KnkPermissible knkPermissible, UserCache userCache, UsersCommandApi usersCommandApi) {
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.knkPermissible = Objects.requireNonNull(knkPermissible, "knkPermissible must not be null");
        this.userCache = Objects.requireNonNull(userCache, "userCache must not be null");
        this.usersCommandApi = usersCommandApi; // null when the API client isn't wired - persistence then no-ops
    }

    public static String nodeFor(ActiveMode mode) {
        return switch (mode) {
            case OWNER -> OWNER_NODE;
            case STAFF -> STAFF_NODE;
            case NONE -> throw new IllegalArgumentException("ActiveMode.NONE has no permission node");
        };
    }

    public static String displayName(ActiveMode mode) {
        return switch (mode) {
            case OWNER -> "owner mode";
            case STAFF -> "staff mode";
            case NONE -> "no mode";
        };
    }

    public ActiveMode getActiveMode(Player player) {
        return activeModes.getOrDefault(player.getUniqueId(), ActiveMode.NONE);
    }

    public boolean isVanished(Player player) {
        return getActiveMode(player).isVanished();
    }

    /**
     * The mode last persisted for this player, as seen through the local user cache (populated at
     * pre-login and kept in sync by {@link #persist}). NONE when nothing is cached. Reads stale
     * entries too: the cache TTL is short and nothing refreshes an online player's entry, but
     * this plugin is the only writer of the mode, so the cached value stays correct.
     */
    public ActiveMode getPersistedMode(Player player) {
        return userCache.getStale(player.getUniqueId()).map(UserSummary::activeMode).orElse(ActiveMode.NONE);
    }

    /**
     * Switch the player's in-session mode and update visibility accordingly (main thread only).
     * Does not persist - callers decide whether the change should survive a restart.
     */
    public void applyMode(Player player, ActiveMode mode, boolean notify) {
        ActiveMode previous = getActiveMode(player);
        if (mode == ActiveMode.NONE) {
            activeModes.remove(player.getUniqueId());
        } else {
            activeModes.put(player.getUniqueId(), mode);
        }
        refreshVisibilityOf(player);

        if (!notify || previous == mode) {
            return;
        }
        if (mode == ActiveMode.NONE) {
            sendToggleActionBar(player, previous, false);
        } else {
            sendToggleActionBar(player, mode, true);
        }
    }

    /**
     * Persist a mode for this player: updates the local user cache immediately (so the next
     * login on this server restores it even before the API write lands) and writes it through to
     * knk-web-api. Returns a future that completes exceptionally if the API write failed.
     */
    public CompletableFuture<Void> persist(Player player, ActiveMode mode) {
        return persist(player, mode, usersCommandApi);
    }

    /**
     * {@link #persist(Player, ActiveMode)} through a specific command API - the Player manager
     * (InventoryMenu content port CP8) passes one attributed to the acting staff member
     * ({@code UsersCommandApi.withActor}) so the change is audit-logged under them.
     */
    public CompletableFuture<Void> persist(Player player, ActiveMode mode, UsersCommandApi api) {
        UUID uuid = player.getUniqueId();
        UserSummary cached = userCache.getStale(uuid).orElse(null);
        if (cached == null || cached.id() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No cached knk user for " + uuid));
        }
        userCache.put(cached.withActiveMode(mode));

        if (api == null) {
            return CompletableFuture.completedFuture(null);
        }
        return api.setActiveModeById(cached.id(), mode).whenComplete((ignored, ex) -> {
            if (ex != null) {
                LOGGER.log(Level.WARNING, "Failed to persist active mode " + mode + " for user " + cached.id(), ex);
            }
        });
    }

    /**
     * Re-evaluate whether {@code target} should be hidden from every other online player (main
     * thread only). Hides immediately from everyone when vanished, then reveals to each viewer
     * whose staff/owner visibility check resolves true.
     */
    public void refreshVisibilityOf(Player target) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(target)) {
                applyVisibility(viewer, target);
            }
        }
    }

    /**
     * Re-evaluate which currently-vanished players {@code viewer} may see (main thread only) -
     * used when a player joins, so they don't see anyone already vanished.
     */
    public void refreshVisibilityFor(Player viewer) {
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (!target.equals(viewer)) {
                applyVisibility(viewer, target);
            }
        }
    }

    /**
     * Resolve whether the player really holds {@code mode}'s node (async-safe; the consumer is
     * always invoked on the main thread).
     */
    public void whenHasModePermission(Player player, ActiveMode mode, Consumer<Boolean> onMainThread) {
        onMain(knkPermissible.hasPermissionAsync(player, nodeFor(mode)), onMainThread);
    }

    public void forget(Player player) {
        activeModes.remove(player.getUniqueId());
    }

    private void applyVisibility(Player viewer, Player target) {
        if (!isVanished(target)) {
            viewer.showPlayer(plugin, target);
            return;
        }

        viewer.hidePlayer(plugin, target);
        CompletableFuture<Boolean> canSee = knkPermissible.hasPermissionAsync(viewer, STAFF_NODE)
            .thenCombine(knkPermissible.hasPermissionAsync(viewer, OWNER_NODE), (staff, owner) -> staff || owner);
        onMain(canSee, allowed -> {
            // Re-check: either player may have left, or the target left their mode, meanwhile.
            if (allowed && viewer.isOnline() && target.isOnline() && isVanished(target)) {
                viewer.showPlayer(plugin, target);
            }
        });
    }

    private void onMain(CompletableFuture<Boolean> future, Consumer<Boolean> consumer) {
        if (future.isDone() && Bukkit.isPrimaryThread()) {
            // Ops and already-resolved checks: apply inline, avoiding a hide-then-show flicker.
            consumer.accept(future.getNow(false));
            return;
        }
        future.exceptionally(ex -> false).thenAccept(value ->
            Bukkit.getScheduler().runTask(plugin, () -> consumer.accept(value)));
    }

    private static void sendToggleActionBar(Player player, ActiveMode mode, boolean enabled) {
        player.sendActionBar(Component.text("You ").color(ColorOptions.message)
            .append(Component.text(enabled ? "enabled " : "disabled ")
                .color(enabled ? ColorOptions.messageachievement : ColorOptions.falsecommand))
            .append(Component.text(displayName(mode) + "!").color(ColorOptions.message)));
    }
}
