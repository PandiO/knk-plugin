package net.knightsandkings.knk.paper.permissions;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.dataaccess.FetchPolicy;
import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.PermissionsDataAccess;
import net.knightsandkings.knk.core.domain.permissions.PermissionCheckResult;
import net.knightsandkings.knk.core.domain.users.UserSummary;

/**
 * Call sites migrating onto the new permission model (docs/specs/user-features/DESIGN.md §2.2)
 * use this instead of raw {@code Player.hasPermission(...)} - the helper flagged as a Phase 1
 * gap in docs/specs/user-features/IMPLEMENTATION_PLAN.md's "§1 status" section.
 * <p>
 * Shape: sync-cache-only, not sync-with-timeout. Most call sites (join/command/chat listeners)
 * run on or near the main thread and need an immediate boolean, but {@link PermissionsDataAccess}
 * is REST-backed and async. Rather than block waiting on a network round trip,
 * {@link #hasPermission(Player, String)} only ever reads whatever is already cached
 * ({@link FetchPolicy#CACHE_ONLY} never performs I/O, so the returned future is always already
 * completed - safe to {@code join()} from any thread, including the main thread) and fails
 * closed (matching DESIGN.md §2.2's undeclared-means-deny convention) when nothing is cached
 * yet, while kicking off a background refresh so the next check for that user/node has a real
 * answer instead of repeating the same fallback. This mirrors the exact
 * cache-first-with-background-refresh shape {@code PlayerListener.onValidateLogin}/
 * {@code triggerBackgroundUserRefresh} already established for {@code UsersDataAccess} - not a
 * new pattern invented for this class.
 * <p>
 * {@link #hasPermissionAsync(OfflinePlayer, String)} is for call sites that already run off the
 * main thread (or want a real, freshly-resolved answer rather than a cache-only snapshot).
 * <p>
 * Note this resolves purely against the new REST-backed permission model - it does not consult
 * Bukkit's own permission tree/op status at all. Until a real {@code PermissionGrant}/group
 * exists for a given node (authoring is docs/specs/user-features/IMPLEMENTATION_PLAN.md §6.2, a
 * later phase), every check for it resolves UNDECLARED and therefore fails closed for everyone,
 * including a Minecraft-op'd server owner.
 */
public class KnkPermissible {

    private static final Logger LOGGER = Logger.getLogger(KnkPermissible.class.getName());

    private final UserCache userCache;
    private final PermissionsDataAccess permissionsDataAccess;

    public KnkPermissible(UserCache userCache, PermissionsDataAccess permissionsDataAccess) {
        this.userCache = Objects.requireNonNull(userCache, "userCache must not be null");
        this.permissionsDataAccess = Objects.requireNonNull(permissionsDataAccess, "permissionsDataAccess must not be null");
    }

    /**
     * Synchronous, main-thread-safe check against the new permission model. Fails closed
     * (returns false) when the player's knk user id isn't resolvable yet from the local cache,
     * or when nothing is cached yet for (userId, node) - never blocks on a network call.
     */
    public boolean hasPermission(Player player, String node) {
        return hasPermission((OfflinePlayer) player, node);
    }

    /**
     * Same as {@link #hasPermission(Player, String)}, for {@link OfflinePlayer} call sites.
     */
    public boolean hasPermission(OfflinePlayer player, String node) {
        Integer userId = resolveUserId(player.getUniqueId());
        if (userId == null) {
            return false;
        }
        return checkCacheOnly(userId, node);
    }

    /**
     * Async variant for call sites that already run off the main thread, or that want a real,
     * freshly-resolved answer rather than a cache-only snapshot.
     */
    public CompletableFuture<Boolean> hasPermissionAsync(OfflinePlayer player, String node) {
        Integer userId = resolveUserId(player.getUniqueId());
        if (userId == null) {
            return CompletableFuture.completedFuture(false);
        }

        return permissionsDataAccess.checkAsync(userId, node)
            .thenApply(result -> result.value().map(PermissionCheckResult::isAllowed).orElse(false))
            .exceptionally(ex -> {
                LOGGER.log(Level.WARNING, "Permission check failed for user " + userId + ", node " + node, ex);
                return false;
            });
    }

    private Integer resolveUserId(UUID uuid) {
        return userCache.getByUuid(uuid).map(UserSummary::id).orElse(null);
    }

    private boolean checkCacheOnly(int userId, String node) {
        FetchResult<PermissionCheckResult> cached =
            permissionsDataAccess.checkAsync(userId, node, FetchPolicy.CACHE_ONLY).join();

        if (cached.isSuccess()) {
            return cached.value().map(PermissionCheckResult::isAllowed).orElse(false);
        }

        // Nothing cached yet - fail closed for this call, but warm the cache asynchronously so
        // the next check for this user/node has a real answer instead of repeating this fallback.
        permissionsDataAccess.checkAsync(userId, node).exceptionally(ex -> {
            LOGGER.log(Level.WARNING, "Background permission refresh failed for user " + userId + ", node " + node, ex);
            return null;
        });
        return false;
    }
}
