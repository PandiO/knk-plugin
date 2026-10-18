package net.knightsandkings.knk.core.dataaccess;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.cache.BaseCache;
import net.knightsandkings.knk.core.domain.permissions.EffectivePermissionResolver;
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
 * <p>
 * KNG-171: built with {@link DataAccessSettings#apiOutage()}, so while knk-web-api is DOWN a check
 * that isn't in the fresh cache makes no API call and ends at once as an ERROR result
 * ({@link PermissionDecision#UNAVAILABLE}); the caller answers it from the offline store.
 * <p>
 * KNG-107: with {@link #resolveFromEffectiveSets} on, a check is answered from the user's effective
 * set - one API call per user per TTL, however many nodes are asked, with concurrent loads of the
 * same user's set shared - resolved locally by {@link EffectivePermissionResolver}. A cache-only
 * check may answer from a set that expired up to {@code staleFor} ago while it is reloaded in the
 * background, so listings don't flicker to "no" at every TTL. An API whose sets carry no ranks
 * switches this back to single-node checks.
 */
public class PermissionsDataAccess {

    private static final Logger LOGGER = Logger.getLogger(PermissionsDataAccess.class.getName());

    private final CheckCache checkCache;
    private final EffectiveCache effectiveCache;
    private final Duration effectiveTtl;
    private final PermissionsApi api;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<PermissionCheckKey, PermissionCheckResult> checkExecutor;
    private final DataAccessExecutor<Integer, EffectivePermissionSet> effectiveExecutor;
    private volatile AnswerListener answerListener = (userId, node, result) -> { };
    private volatile boolean resolveFromEffectiveSets;
    private volatile Duration staleFor = Duration.ZERO;
    private volatile LongSupplier clockMillis = System::currentTimeMillis;
    /** Set when the API sent a set without ranks: back to single-node checks for good. */
    private volatile boolean effectiveSetsUnranked;
    private final Map<Integer, CompletableFuture<FetchResult<EffectivePermissionSet>>> effectiveLoads = new ConcurrentHashMap<>();
    private final Map<Integer, LoadedSet> loadedSets = new ConcurrentHashMap<>();

    /**
     * Told about every answer the API gives a check (KNG-58: the offline security cache keeps the
     * last one). {@code result} is null when the API knows no such user ({@code node} is then null when
     * it was an effective-set load that found no user, KNG-107).
     */
    @FunctionalInterface
    public interface AnswerListener {
        void answered(int userId, String node, PermissionCheckResult result);
    }

    private record PermissionCheckKey(int userId, String node) {
    }

    /**
     * A user's set as last loaded: its resolver, when it was loaded, and the nodes already passed to the
     * {@link AnswerListener} from it (each once per load, as single-node checks did once per fetch).
     */
    private record LoadedSet(EffectivePermissionSet set, EffectivePermissionResolver resolver, long loadedAtMillis,
                             Set<String> reported) {
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
        this.effectiveTtl = ttl != null ? ttl : Duration.ofMinutes(1);
        this.api = Objects.requireNonNull(api, "api must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.checkExecutor = new DataAccessExecutor<>(checkCache, this.settings, "PermissionCheck");
        this.effectiveExecutor = new DataAccessExecutor<>(effectiveCache, this.settings, "EffectivePermissions");
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
        if (resolveFromEffectiveSets && !effectiveSetsUnranked) {
            return checkFromEffectiveSet(userId, node, effective);
        }
        return checkSingleNode(userId, node, effective);
    }

    private CompletableFuture<FetchResult<PermissionCheckResult>> checkSingleNode(int userId, String node, FetchPolicy effective) {
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
     * KNG-107: answer checks from each user's effective set (see the class comment). {@code staleFor}: how
     * long past its TTL a set may still answer a cache-only check while it is reloaded.
     */
    public void resolveFromEffectiveSets(Duration staleFor) {
        resolveFromEffectiveSets(staleFor, System::currentTimeMillis);
    }

    /** As {@link #resolveFromEffectiveSets(Duration)} with the clock the stale window is measured on (tests). */
    void resolveFromEffectiveSets(Duration staleFor, LongSupplier clockMillis) {
        this.staleFor = staleFor == null || staleFor.isNegative() ? Duration.ZERO : staleFor;
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis must not be null");
        this.resolveFromEffectiveSets = true;
    }

    /**
     * Loads the user's effective set unless a fresh one is cached (KNG-107: at join, so the player's first
     * command or listing needs no API call). Does nothing unless checks resolve from effective sets.
     */
    public CompletableFuture<Void> preload(int userId) {
        if (!resolveFromEffectiveSets || effectiveSetsUnranked) {
            return CompletableFuture.completedFuture(null);
        }
        return loadEffective(userId).thenAccept(result -> { });
    }

    private CompletableFuture<FetchResult<PermissionCheckResult>> checkFromEffectiveSet(int userId, String node, FetchPolicy policy) {
        if (policy == FetchPolicy.CACHE_ONLY) {
            return CompletableFuture.completedFuture(checkCachedSet(userId, node));
        }
        CompletableFuture<FetchResult<EffectivePermissionSet>> load = policy == FetchPolicy.CACHE_FIRST
            ? loadEffective(userId)
            : getEffectiveAsync(userId, policy);
        return load.thenCompose(result -> {
            if (!result.isSuccess()) {
                return CompletableFuture.completedFuture(result.map(set -> (PermissionCheckResult) null));
            }
            LoadedSet loaded = loaded(userId, result.value().orElseThrow());
            if (loaded == null) {
                return checkSingleNode(userId, node, policy);
            }
            return CompletableFuture.completedFuture(result.map(set -> answer(userId, node, loaded)));
        });
    }

    /** Cache-only: the fresh set, else one expired less than {@code staleFor} ago (and reload it), else a miss. */
    private FetchResult<PermissionCheckResult> checkCachedSet(int userId, String node) {
        Optional<EffectivePermissionSet> fresh = effectiveCache.get(userId);
        if (fresh.isPresent()) {
            LoadedSet loaded = loaded(userId, fresh.get());
            return loaded != null ? FetchResult.hit(answer(userId, node, loaded)) : checkSingleNodeCacheOnly(userId, node);
        }
        LoadedSet last = loadedSets.get(userId);
        if (last != null && clockMillis.getAsLong() - last.loadedAtMillis() <= settingsTtlMillis() + staleFor.toMillis()) {
            loadEffective(userId);
            // Not passed to the answer listener: the offline store keeps API answers, not expired ones.
            return FetchResult.staleServed(last.resolver().resolve(node));
        }
        return FetchResult.notFound();
    }

    private FetchResult<PermissionCheckResult> checkSingleNodeCacheOnly(int userId, String node) {
        return checkSingleNode(userId, node, FetchPolicy.CACHE_ONLY).join();
    }

    private long settingsTtlMillis() {
        return effectiveTtl.toMillis();
    }

    /** One load of the user's set at a time: concurrent checks on a cold cache share it. */
    private CompletableFuture<FetchResult<EffectivePermissionSet>> loadEffective(int userId) {
        Optional<EffectivePermissionSet> fresh = effectiveCache.get(userId);
        if (fresh.isPresent()) {
            return CompletableFuture.completedFuture(FetchResult.hit(fresh.get()));
        }
        CompletableFuture<FetchResult<EffectivePermissionSet>> running = effectiveLoads.get(userId);
        if (running != null) {
            return running;
        }
        CompletableFuture<FetchResult<EffectivePermissionSet>> load = new CompletableFuture<>();
        running = effectiveLoads.putIfAbsent(userId, load);
        if (running != null) {
            return running;
        }
        getEffectiveAsync(userId, FetchPolicy.CACHE_FIRST).whenComplete((result, error) -> {
            effectiveLoads.remove(userId, load);
            if (result != null && result.isSuccess()) {
                loaded(userId, result.value().orElseThrow());
            } else if (result != null && result.status() == FetchStatus.NOT_FOUND) {
                answerListener.answered(userId, null, null); // the API knows no such user (KNG-58 erasure)
            }
            load.complete(error != null ? FetchResult.error(error) : result);
        });
        return load;
    }

    /** The loaded state for {@code set} (kept per user, replaced by a newer set), or null when it carries no ranks. */
    private LoadedSet loaded(int userId, EffectivePermissionSet set) {
        LoadedSet current = loadedSets.get(userId);
        if (current != null && current.set() == set) {
            return current;
        }
        Optional<EffectivePermissionResolver> resolver = EffectivePermissionResolver.of(set);
        if (resolver.isEmpty()) {
            if (!effectiveSetsUnranked) {
                effectiveSetsUnranked = true;
                LOGGER.warning("knk-web-api sends effective permission sets without ranks (an older API): "
                    + "permission checks go to the API one node at a time again");
            }
            return null;
        }
        LoadedSet loaded = new LoadedSet(set, resolver.get(), clockMillis.getAsLong(), ConcurrentHashMap.newKeySet());
        loadedSets.put(userId, loaded);
        return loaded;
    }

    private PermissionCheckResult answer(int userId, String node, LoadedSet loaded) {
        PermissionCheckResult result = loaded.resolver().resolve(node);
        if (loaded.reported().add(node)) {
            answerListener.answered(userId, node, result);
        }
        return result;
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
     * {@code UNAVAILABLE} when the API couldn't be asked (error after retries, or the API is DOWN,
     * with no cached answer).
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
        loadedSets.clear();
    }

    /**
     * Invalidate just one user's cached effective set (e.g. after a targeted admin action). With checks
     * resolved from effective sets, the user's next check loads the set again.
     */
    public void invalidateEffective(int userId) {
        effectiveExecutor.invalidate(userId);
        loadedSets.remove(userId);
    }
}
