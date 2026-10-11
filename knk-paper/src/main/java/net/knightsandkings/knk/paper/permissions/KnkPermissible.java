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
import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.offline.OfflineSecurityStore;

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
 * Op bypass: a Minecraft-op'd player short-circuits to {@code true} on every node, the same way
 * Bukkit's own {@code hasPermission} treats an undeclared node for an op. This is a deliberate
 * exception to "resolves purely through the new model" - without it, every check would fail
 * closed for everyone (including the server owner) until a real {@code PermissionGrant}/group
 * exists for that node, and authoring one is docs/specs/user-features/IMPLEMENTATION_PLAN.md
 * §6.2, a later phase not built yet. A real grant/deny from the new model still takes over
 * normally for a non-op.
 * <p>
 * KNG-58: when the API can't answer - the account isn't cached (restart during an outage) or a
 * check can't be made - the last answers the API gave are taken from the
 * {@link OfflineSecurityStore} (on disk, bounded by age; an unknown or too-old answer still means
 * no). The same store fills the gap after the 30 s memory TTL, so the first check after it no
 * longer fails closed while the refresh is under way.
 */
public class KnkPermissible {

    private static final Logger LOGGER = Logger.getLogger(KnkPermissible.class.getName());

    private final UserCache userCache;
    private final PermissionsDataAccess permissionsDataAccess;
    private final OfflineSecurityStore offline;

    public KnkPermissible(UserCache userCache, PermissionsDataAccess permissionsDataAccess) {
        this(userCache, permissionsDataAccess, null);
    }

    /** @param offline the last-known answers for when the API can't be asked (KNG-58); null = none */
    public KnkPermissible(UserCache userCache, PermissionsDataAccess permissionsDataAccess, OfflineSecurityStore offline) {
        this.userCache = Objects.requireNonNull(userCache, "userCache must not be null");
        this.permissionsDataAccess = Objects.requireNonNull(permissionsDataAccess, "permissionsDataAccess must not be null");
        this.offline = offline;
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
        if (player.isOp()) {
            return true;
        }

        Integer userId = resolveUserId(player.getUniqueId());
        if (userId == null) {
            return false;
        }
        return checkCacheOnly(userId, node);
    }

    /**
     * Async variant for call sites that already run off the main thread, or that want a real,
     * freshly-resolved answer rather than a cache-only snapshot. False both for a real denial and
     * when the check couldn't be made - use {@link #checkAsync} to tell those apart.
     */
    public CompletableFuture<Boolean> hasPermissionAsync(OfflinePlayer player, String node) {
        return checkAsync(player, node).thenApply(PermissionDecision::allowed);
    }

    /**
     * {@link #hasPermissionAsync} with the reason for a "no": {@link PermissionDecision#DENIED} is a
     * real answer from the permission model, {@link PermissionDecision#UNAVAILABLE} means it
     * couldn't be asked - knk-web-api unreachable with nothing cached, or the player's account
     * never loaded (the API was down when they joined). Callers still refuse on UNAVAILABLE but
     * should say the service is down rather than "You don't have permission" (currency smoke
     * test, 2026-09-27). Ops are always ALLOWED. Never completes exceptionally.
     */
    public CompletableFuture<PermissionDecision> checkAsync(OfflinePlayer player, String node) {
        if (player.isOp()) {
            return CompletableFuture.completedFuture(PermissionDecision.ALLOWED);
        }

        Integer userId = resolveUserId(player.getUniqueId());
        if (userId == null) {
            return CompletableFuture.completedFuture(PermissionDecision.UNAVAILABLE);
        }

        return permissionsDataAccess.checkAsync(userId, node)
            .thenApply(result -> {
                PermissionDecision decision = PermissionsDataAccess.decide(result);
                if (decision == PermissionDecision.UNAVAILABLE) {
                    LOGGER.log(Level.WARNING, "Permission check failed for user " + userId + ", node " + node,
                        result != null ? result.error().orElse(null) : null);
                    return lastKnown(userId, node);
                }
                return decision;
            })
            .exceptionally(ex -> {
                LOGGER.log(Level.WARNING, "Permission check failed for user " + userId + ", node " + node, ex);
                return lastKnown(userId, node);
            });
    }

    private Integer resolveUserId(UUID uuid) {
        // getStale, not getByUuid: the user cache's TTL is the short global cache TTL and
        // nothing refreshes an online player's entry mid-session, so a fresh-only read stopped
        // resolving (and every check failed closed for non-ops) about a minute after join. A
        // UUID's knk user id never changes, so an expired entry is still a correct answer here.
        Integer cached = userCache.getStale(uuid).map(UserSummary::id).orElse(null);
        if (cached != null || offline == null) {
            return cached;
        }
        // Not loaded this session (restart while the API is down): the account the API last named.
        return offline.identity(uuid).map(OfflineSecurityStore.Identity::userId).orElse(null);
    }

    /** The API's last answer for this user and node (KNG-58), else UNAVAILABLE. */
    private PermissionDecision lastKnown(int userId, String node) {
        if (offline == null) {
            return PermissionDecision.UNAVAILABLE;
        }
        return offline.permission(userId, node).map(PermissionDecision::of).orElse(PermissionDecision.UNAVAILABLE);
    }

    private boolean checkCacheOnly(int userId, String node) {
        FetchResult<PermissionCheckResult> cached =
            permissionsDataAccess.checkAsync(userId, node, FetchPolicy.CACHE_ONLY).join();

        if (cached.isSuccess()) {
            return cached.value().map(PermissionCheckResult::isAllowed).orElse(false);
        }

        // Nothing fresh in memory - refresh in the background so the next check has a real answer,
        // and answer this one with the API's last answer if we have one (KNG-58), else fail closed.
        permissionsDataAccess.checkAsync(userId, node).exceptionally(ex -> {
            LOGGER.log(Level.WARNING, "Background permission refresh failed for user " + userId + ", node " + node, ex);
            return null;
        });
        return offline != null && offline.permission(userId, node).orElse(false);
    }
}
