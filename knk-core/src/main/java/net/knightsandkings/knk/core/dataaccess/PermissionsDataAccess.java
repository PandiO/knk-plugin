package net.knightsandkings.knk.core.dataaccess;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.domain.permissions.EffectivePermissionSet;
import net.knightsandkings.knk.core.domain.permissions.PermissionCheckResult;
import net.knightsandkings.knk.core.domain.permissions.PermissionDecision;
import net.knightsandkings.knk.core.ports.api.PermissionsApi;

/**
 * Data access gateway for permission checks/effective-set lookups.
 * <p>
 * Cache-first, API-fallback, same shape as every other *DataAccess gateway (see
 * {@link UsersDataAccess}/{@link EnchantmentDefinitionsDataAccess}) - but with a deliberately
 * short TTL, since permission checks are latency-sensitive (called on nearly every gated action)
 * and need to reflect a grant/revoke reasonably promptly, unlike mostly-static catalog data.
 * <p>
 * Two independent caches: one for single-node check results (keyed by user+node, since that's
 * the shape knk-plugin calls constantly), one for full effective-permission sets (keyed by user,
 * used by admin/profile tooling). There is no write path here in Phase 1 - group/grant authoring
 * commands don't exist yet (see docs/specs/user-features/IMPLEMENTATION_PLAN.md §6.2, a later
 * phase), so {@link #invalidateAll()} is the only invalidation available for now; a future phase
 * wiring up in-game grant/revoke commands should call it after a mutation rather than waiting out
 * the TTL.
 */
public class PermissionsDataAccess {

    private final CheckCache checkCache;
    private final EffectiveCache effectiveCache;
    private final PermissionsApi api;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<PermissionCheckKey, PermissionCheckResult> checkExecutor;
    private final DataAccessExecutor<Integer, EffectivePermissionSet> effectiveExecutor;
    private volatile AnswerListener answerListener = (userId, node, result) -> { };

    /**
     * Told about every answer the API gives a check (KNG-58: the offline security cache keeps the
     * last one). {@code result} is null when the API knows no such user.
     */
    @FunctionalInterface
    public interface AnswerListener {
        void answered(int userId, String node, PermissionCheckResult result);
    }

    private record PermissionCheckKey(int userId, String node) {
    }

    private static class CheckCache extends BaseCache<PermissionCheckKey, PermissionCheckResult> {
        CheckCache(Duration ttl) {
            super(ttl);
        }
    }

    private static class EffectiveCache extends BaseCache<Integer, EffectivePermissionSet> {
        EffectiveCache(Duration ttl) {
            super(ttl);
        }
    }

    public PermissionsDataAccess(Duration ttl, PermissionsApi api) {
        this(ttl, api, DataAccessSettings.defaults());
    }

    public PermissionsDataAccess(Duration ttl, PermissionsApi api, DataAccessSettings settings) {
        this.checkCache = new CheckCache(ttl);
        this.effectiveCache = new EffectiveCache(ttl);
        this.api = Objects.requireNonNull(api, "api must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.checkExecutor = new DataAccessExecutor<>(checkCache, this.settings.retryPolicy(), "PermissionCheck");
        this.effectiveExecutor = new DataAccessExecutor<>(effectiveCache, this.settings.retryPolicy(), "EffectivePermissions");
    }

    /**
     * Check a single node using the specified fetch policy.
     * <p>
     * Async method; safe for event threads.
     *
     * @param userId The user to check.
     * @param node   The permission node to check.
     * @param policy The fetch policy (defaults to CACHE_FIRST if null).
     * @return CompletableFuture resolving to FetchResult&lt;PermissionCheckResult&gt;.
     */
    public CompletableFuture<FetchResult<PermissionCheckResult>> checkAsync(int userId, String node, FetchPolicy policy) {
        Objects.requireNonNull(node, "node must not be null");
        FetchPolicy effective = settings.resolvePolicy(policy);
        PermissionCheckKey key = new PermissionCheckKey(userId, node);

        return checkExecutor.fetchAsync(
            key,
            effective,
            () -> api.check(userId, node).thenApply(result -> {
                answerListener.answered(userId, node, result);
                return result;
            })
        );
    }

    public void setAnswerListener(AnswerListener listener) {
        this.answerListener = listener != null ? listener : (userId, node, result) -> { };
    }

    /**
     * Check a single node using CACHE_FIRST policy (default).
     */
    public CompletableFuture<FetchResult<PermissionCheckResult>> checkAsync(int userId, String node) {
        return checkAsync(userId, node, null);
    }

    /**
     * {@link #checkAsync(int, String)} as a {@link PermissionDecision}: {@code ALLOWED} for a
     * grant, {@code DENIED} for a real "no" (deny, undeclared, or a user the API doesn't know),
     * {@code UNAVAILABLE} when the API couldn't be asked (error after retries, no cached answer).
     * Never completes exceptionally.
     */
    public CompletableFuture<PermissionDecision> decideAsync(int userId, String node) {
        return checkAsync(userId, node)
            .thenApply(PermissionsDataAccess::decide)
            .exceptionally(ex -> PermissionDecision.UNAVAILABLE);
    }

    /** A check's fetch result as a decision (see {@link #decideAsync}). */
    public static PermissionDecision decide(FetchResult<PermissionCheckResult> result) {
        if (result == null) {
            return PermissionDecision.UNAVAILABLE;
        }
        if (result.isSuccess()) {
            return PermissionDecision.of(result.value().map(PermissionCheckResult::isAllowed).orElse(false));
        }
        return result.status() == FetchStatus.NOT_FOUND ? PermissionDecision.DENIED : PermissionDecision.UNAVAILABLE;
    }

    /**
     * Retrieve a user's full resolved permission set using the specified fetch policy.
     * <p>
     * Async method; safe for event threads.
     *
     * @param userId The user to resolve.
     * @param policy The fetch policy (defaults to CACHE_FIRST if null).
     * @return CompletableFuture resolving to FetchResult&lt;EffectivePermissionSet&gt;.
     */
    public CompletableFuture<FetchResult<EffectivePermissionSet>> getEffectiveAsync(int userId, FetchPolicy policy) {
        FetchPolicy effective = settings.resolvePolicy(policy);

        return effectiveExecutor.fetchAsync(
            userId,
            effective,
            () -> api.getEffective(userId)
        );
    }

    /**
     * Retrieve a user's full resolved permission set using CACHE_FIRST policy (default).
     */
    public CompletableFuture<FetchResult<EffectivePermissionSet>> getEffectiveAsync(int userId) {
        return getEffectiveAsync(userId, null);
    }

    /**
     * Invalidate every cached check result and effective set. Check results are keyed by
     * (userId, node), so there's no cheap way to invalidate just one user's entries without
     * scanning - this is the broad, safe option to call after any grant/group-membership change.
     */
    public void invalidateAll() {
        checkExecutor.invalidateAll();
        effectiveExecutor.invalidateAll();
    }

    /** Invalidate just one user's cached effective set (e.g. after a targeted admin action). */
    public void invalidateEffective(int userId) {
        effectiveExecutor.invalidate(userId);
    }
}
