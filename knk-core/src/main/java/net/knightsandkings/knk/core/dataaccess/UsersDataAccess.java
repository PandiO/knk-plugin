package net.knightsandkings.knk.core.dataaccess;


import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.users.UserDetail;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.UsersCommandApi;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;

/**
 * Data access gateway for Users.
 * <p>
 * Provides cache-first, API-fallback retrieval methods with support for
 * fetch policies, stale reads, invalidation, and background refresh.
 * <p>
 * Thread-safe: All public methods are async and do not block the calling thread.
 */
public class UsersDataAccess {
    
    private static final Logger LOGGER = Logger.getLogger(UsersDataAccess.class.getName());
    
    private final UserCache userCache;
    private final UsersQueryApi usersQueryApi;
    private final UsersCommandApi usersCommandApi;
    private final DataAccessSettings settings;
    private final DataAccessExecutor<UUID, UserSummary> executor;
    private volatile UserAnswerListener answerListener = UserAnswerListener.NONE;

    /**
     * Told about every user the API returns, or that it knows no user for a UUID (KNG-58: the
     * offline security cache records the first and forgets the second). Local cache edits
     * (balances, mode) are not API answers and are not reported.
     */
    public interface UserAnswerListener {
        UserAnswerListener NONE = new UserAnswerListener() {
            @Override
            public void found(UserSummary user) {
            }

            @Override
            public void notFound(UUID uuid) {
            }
        };

        void found(UserSummary user);

        void notFound(UUID uuid);
    }

    public void setAnswerListener(UserAnswerListener listener) {
        this.answerListener = listener != null ? listener : UserAnswerListener.NONE;
    }

    private UserSummary reportByUuid(UUID uuid, UserSummary userSummary) {
        if (userSummary != null) {
            userCache.put(userSummary);
            answerListener.found(userSummary);
        } else {
            answerListener.notFound(uuid);
        }
        return userSummary;
    }
    
    /**
     * Create a new UsersDataAccess gateway.
     *
     * @param userCache The user cache instance
     * @param usersQueryApi The users query API port
     * @param usersCommandApi The users command API port
     */
    public UsersDataAccess(
        UserCache userCache,
        UsersQueryApi usersQueryApi,
        UsersCommandApi usersCommandApi
    ) {
        this(userCache, usersQueryApi, usersCommandApi, DataAccessSettings.defaults());
    }

    public UsersDataAccess(
        UserCache userCache,
        UsersQueryApi usersQueryApi,
        UsersCommandApi usersCommandApi,
        DataAccessSettings settings
    ) {
        this.userCache = Objects.requireNonNull(userCache, "userCache must not be null");
        this.usersQueryApi = Objects.requireNonNull(usersQueryApi, "usersQueryApi must not be null");
        this.usersCommandApi = Objects.requireNonNull(usersCommandApi, "usersCommandApi must not be null");
        this.settings = Objects.requireNonNullElse(settings, DataAccessSettings.defaults());
        this.executor = new DataAccessExecutor<>(userCache, this.settings.retryPolicy(), "User");
    }
    
    /**
     * Retrieve a user by UUID using the specified fetch policy.
     * <p>
     * Async method; safe for event threads. Do not block on the result
     * in sync contexts; use background handlers or listener continuations.
     *
     * @param uuid The player UUID
     * @param policy The fetch policy (defaults to CACHE_FIRST if null)
     * @return CompletableFuture resolving to FetchResult<UserSummary>
     */
    public CompletableFuture<FetchResult<UserSummary>> getByUuidAsync(
        UUID uuid,
        FetchPolicy policy
    ) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        policy = settings.resolvePolicy(policy);
        
        return executor.fetchAsync(
            uuid,
            policy,
            () -> usersQueryApi.getByUuid(uuid).thenApply(userSummary -> reportByUuid(uuid, userSummary))
        );
    }
    
    /**
     * Retrieve a user by UUID using CACHE_FIRST policy (default).
     * <p>
     * Convenience method equivalent to getByUuidAsync(uuid, CACHE_FIRST).
     *
     * @param uuid The player UUID
     * @return CompletableFuture resolving to FetchResult<UserSummary>
     */
    public CompletableFuture<FetchResult<UserSummary>> getByUuidAsync(UUID uuid) {
        return getByUuidAsync(uuid, null);
    }
    
    /**
     * Retrieve a user by username.
     * <p>
     * Async method; safe for event threads.
     * <p>
     * Note: The cache is UUID-keyed, not username-keyed. Username lookups
     * always hit the API, but on success, the result is cached by UUID
     * for future UUID lookups.
     *
     * @param username The player username
     * @return CompletableFuture resolving to FetchResult<UserSummary>
     */
    public CompletableFuture<FetchResult<UserSummary>> getByUsernameAsync(String username) {
        Objects.requireNonNull(username, "username must not be null");
        
        // Username lookups always hit API (cache is UUID-keyed)
        // We call the API directly without using the executor since the cache is UUID-keyed
        return usersQueryApi.getByUsername(username).thenApply(userSummary -> {
            if (userSummary != null) {
                userCache.put(userSummary); // Cache by UUID for future UUID lookups
                answerListener.found(userSummary);
                return FetchResult.<UserSummary>missFetched(userSummary);
            }
            return FetchResult.<UserSummary>notFound();
        }).exceptionally(e -> FetchResult.<UserSummary>error(e));
    }
    
    /**
     * Retrieve or create a user.
     * <p>
     * If the user is not found and create=true, attempts to create a new user
     * via the UsersCommandApi using the provided UserDetail seed.
     * <p>
     * Async method; safe for event threads.
     *
     * @param uuid The player UUID
     * @param create If true, attempt to create if not found
     * @param seed The user seed for creation (ignored if create=false or user exists)
     * @return CompletableFuture resolving to FetchResult<UserSummary>
     */
    public CompletableFuture<FetchResult<UserSummary>> getOrCreateAsync(
        UUID uuid,
        boolean create,
        UserDetail seed
    ) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        
        return getByUuidAsync(uuid, null).thenCompose((FetchResult<UserSummary> result) -> {
            // If found (HIT or MISS_FETCHED with value present), return immediately
            if (result.isSuccess()) {
                return CompletableFuture.completedFuture(result);
            }
            
            // If NOT_FOUND and create=true, attempt creation
            if (result.status() == FetchStatus.NOT_FOUND && create && seed != null) {
                LOGGER.log(Level.INFO, "Creating new user for uuid={0}", uuid);
                return usersCommandApi.create(seed).thenApply(created -> {
                    if (created != null) {
                        // Convert to summary and cache
                        UserSummary summary = new UserSummary(
                            created.id(),
                            created.username(),
                            created.uuid(),
                            created.coins()
                        );
                        userCache.put(summary);
                        answerListener.found(summary);
                        return FetchResult.<UserSummary>missFetched(summary);
                    }
                    return FetchResult.<UserSummary>notFound();
                }).exceptionally(e -> {
                    LOGGER.log(Level.WARNING, "Failed to create user for uuid=" + uuid, e);
                    return FetchResult.<UserSummary>error(e);
                });
            }
            
            // If NOT_FOUND and create=false, return as-is
            return CompletableFuture.completedFuture(result);
        });
    }
    
    /**
     * Refresh user data from the API, bypassing cache.
     * <p>
     * Fetches fresh data from the API and updates the cache.
     *
     * @param uuid The player UUID
     * @return CompletableFuture resolving to FetchResult<UserSummary>
     */
    public CompletableFuture<FetchResult<UserSummary>> refreshAsync(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        
        return executor.fetchAsync(
            uuid,
            settings.resolvePolicy(FetchPolicy.API_ONLY),
            () -> usersQueryApi.getByUuid(uuid).thenApply(userSummary -> reportByUuid(uuid, userSummary))
        );
    }
    
    /**
     * Invalidate a cached user by UUID.
     *
     * @param uuid The player UUID
     */
    public void invalidate(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        executor.invalidate(uuid);
    }
    
    /**
     * Invalidate all cached users.
     */
    public void invalidateAll() {
        executor.invalidateAll();
    }
}
