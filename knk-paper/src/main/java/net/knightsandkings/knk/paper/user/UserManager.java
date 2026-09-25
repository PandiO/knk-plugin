package net.knightsandkings.knk.paper.user;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.api.dto.CreateUserRequestDto;
import net.knightsandkings.knk.api.dto.DuplicateCheckResponseDto;
import net.knightsandkings.knk.api.dto.UserResponseDto;
import net.knightsandkings.knk.core.cache.UserCache;
import net.knightsandkings.knk.core.domain.users.GatePassThroughMethod;
import net.knightsandkings.knk.core.domain.users.UserSummary;
import net.knightsandkings.knk.core.ports.api.UserAccountApi;
import net.knightsandkings.knk.core.ports.api.UsersQueryApi;
import net.knightsandkings.knk.paper.KnKPlugin;
import net.knightsandkings.knk.paper.config.KnkConfig;

/**
 * Manages user account data during player sessions.
 * 
 * Responsibilities:
 * - Fetch and cache user data on player join
 * - Detect duplicate accounts
 * - Create minimal user accounts for new players
 * - Maintain session cache (cleared on quit)
 * 
 * Thread Safety:
 * - Uses ConcurrentHashMap for thread-safe cache operations
 * - API calls are async (CompletableFuture) with proper exception handling
 * - Cache reads are safe (PlayerUserData is immutable)
 */
public class UserManager {
    private final KnKPlugin plugin;
    private final UserAccountApi userAccountApi;
    private final UsersQueryApi usersQueryApi;
    private final UserCache legacyUserCache;  // Legacy cache for PlayerListener compatibility
    private final Logger logger;
    private final KnkConfig.AccountConfig accountConfig;
    private final KnkConfig.MessagesConfig messagesConfig;
    
    // Thread-safe cache of player data indexed by UUID
    private final ConcurrentHashMap<UUID, PlayerUserData> userCache = new ConcurrentHashMap<>();
    
    public UserManager(
        KnKPlugin plugin,
        UserAccountApi userAccountApi,
        UsersQueryApi usersQueryApi,
        UserCache legacyUserCache,
        Logger logger,
        KnkConfig.AccountConfig accountConfig,
        KnkConfig.MessagesConfig messagesConfig
    ) {
        this.plugin = plugin;
        this.userAccountApi = userAccountApi;
        this.usersQueryApi = usersQueryApi;
        this.legacyUserCache = legacyUserCache;
        this.logger = logger;
        this.accountConfig = accountConfig;
        this.messagesConfig = messagesConfig;
    }
    
    /**
     * Called on player join to sync user data from API.
     * Returns PlayerUserData representing the player's account state.
     *
     * Blocks the calling thread until the API round trip completes - only safe to call
     * off the main thread (e.g. from tests, or from within an already-async context).
     * The live join path uses {@link #onPlayerJoinAsync(Player)} instead so the server's
     * main thread is never blocked waiting on the web API.
     *
     * @param player The player joining the server
     * @return PlayerUserData for the player (never null)
     */
    public PlayerUserData onPlayerJoin(Player player) {
        return onPlayerJoinAsync(player).join();
    }

    /**
     * Async equivalent of {@link #onPlayerJoin(Player)}. Never blocks the calling thread -
     * every step is chained via CompletableFuture, including the API calls themselves.
     *
     * Flow:
     * 1. Check for duplicate accounts (UUID + username)
     * 2. If duplicate found, create conflict entry in cache
     * 3. If no duplicate, create minimal user account
     * 4. Cache the result for session duration
     *
     * Any failure along the way resolves to a fallback minimal PlayerUserData rather than
     * failing the future, matching the previous synchronous behavior.
     *
     * @param player The player joining the server
     * @return a future that always completes normally with a non-null PlayerUserData
     */
    public CompletableFuture<PlayerUserData> onPlayerJoinAsync(Player player) {
        UUID uuid = player.getUniqueId();
        String username = player.getName();

        logger.info("Checking for duplicate accounts for " + username + " (" + uuid + ")");

        return userAccountApi.checkDuplicate(uuid.toString(), username)
            .thenCompose(duplicateCheckObj -> {
                DuplicateCheckResponseDto duplicateCheck = (DuplicateCheckResponseDto) duplicateCheckObj;

                if (duplicateCheck.hasDuplicate()) {
                    // Duplicate detected - create conflict entry
                    logger.warning("Duplicate account detected for " + username + " (UUID: " + uuid + ")");
                    return CompletableFuture.completedFuture(handleDuplicateAccount(player, duplicateCheck));
                } else {
                    // No duplicate - create or fetch minimal user
                    logger.info("No duplicate found for " + username + ", creating/fetching user");
                    return createOrFetchMinimalUserAsync(player);
                }
            })
            // Hard cap on the whole lookup/create chain (not just the first call) - guarantees
            // this future always settles, so a player is never stuck in JoinLoadingGuard's
            // spectator hold indefinitely if the web API hangs.
            .orTimeout(JoinLoadingGuard.safetyTimeoutSeconds(), TimeUnit.SECONDS)
            .thenApply(userData -> {
                // Cache the user data
                userCache.put(uuid, userData);
                logger.info("Cached user data for " + username + " (ID: " + userData.userId() + ")");
                return userData;
            })
            .exceptionally(ex -> {
                logger.severe("Failed to sync user data for " + username + " (" + uuid + "): " + ex.getMessage());
                ex.printStackTrace();

                // Create fallback minimal entry
                PlayerUserData fallback = PlayerUserData.minimal(uuid, username, null);
                userCache.put(uuid, fallback);
                return fallback;
            });
    }
    
    /**
     * Handle duplicate account detection.
     * Creates a PlayerUserData entry marking the conflict for UI prompting.
     */
    private PlayerUserData handleDuplicateAccount(Player player, DuplicateCheckResponseDto duplicateCheck) {
        UUID uuid = player.getUniqueId();
        String username = player.getName();
        
        // Get primary and conflicting user info
        UserResponseDto primaryUser = duplicateCheck.primaryUser();
        UserResponseDto conflictingUser = duplicateCheck.conflictingUser();
        
        logger.info("Duplicate account details: Primary=" + primaryUser.id() + 
                    ", Conflicting=" + conflictingUser.id());
        
        // Create user data with conflict flag
        return PlayerUserData.withConflict(
            uuid,
            username,
            primaryUser.id(),
            conflictingUser.id()
        );
    }
    
    /**
     * Create a minimal user account (UUID + username only) if not exists.
     * Used for players joining for the first time without email/password.
     *
     * Chains the lookup/create API calls via CompletableFuture instead of blocking on
     * each with .join(), so the caller's thread (the server main thread, on the join
     * path) is never blocked waiting on the web API.
     */
    private CompletableFuture<PlayerUserData> createOrFetchMinimalUserAsync(Player player) {
        UUID uuid = player.getUniqueId();
        String username = player.getName();

        // Check if user already exists by UUID
        return usersQueryApi.getByUuid(uuid).thenCompose(existingByUuid -> {
            if (existingByUuid != null) {
                logger.info("User already exists for UUID " + uuid + ": " + existingByUuid.username());
                //Log all user details for debugging
                logger.info("User details: ID=" + existingByUuid.id() +
                           ", Username=" + existingByUuid.username() +
                           ", UUID=" + existingByUuid.uuid() +
                           ", Email=" + existingByUuid.email());
                return CompletableFuture.completedFuture(mapToPlayerUserData(existingByUuid, uuid, false)); // Not new - already exists
            }

            // Check if user exists by username (web app first flow)
            return usersQueryApi.getByUsername(username).thenCompose(existingByUsername -> {
                if (existingByUsername != null && existingByUsername.uuid() != null) {
                    logger.info("User already exists for username " + username + " with UUID");
                    //Log all user details for debugging
                    logger.info("User details: ID=" + existingByUsername.id() +
                               ", Username=" + existingByUsername.username() +
                               ", UUID=" + existingByUsername.uuid() +
                               ", Email=" + existingByUsername.email());
                    return CompletableFuture.completedFuture(mapToPlayerUserData(existingByUsername, uuid, false)); // Not new - already exists
                }

                // Create minimal user via API
                CreateUserRequestDto request = CreateUserRequestDto.minimalUser(
                    uuid.toString(),
                    username
                );

                logger.info("Creating minimal user account for " + username);

                return userAccountApi.createUser(request).thenApply(responseObj -> {
                    UserResponseDto response = (UserResponseDto) responseObj;

                    logger.info("User account created/fetched: ID=" + response.id() +
                               ", Username=" + response.username());

                    // Create UserSummary for legacy cache with isNewUser = true
                    UserSummary newUserSummary = new UserSummary(
                        response.id(),
                        response.username(),
                        uuid,
                        response.email(),
                        response.coins() != null ? response.coins() : 0,
                        response.gems() != null ? response.gems() : 0,
                        response.experiencePoints() != null ? response.experiencePoints() : 0,
                        false,  // isFullAccount - new users don't have email/password yet
                        true,   // isNewUser - we just created this account
                        GatePassThroughMethod.DEFAULT
                    );

                    // Update legacy cache for PlayerListener compatibility
                    legacyUserCache.put(newUserSummary);
                    logger.info("Updated legacy cache for new user " + username + " with isNewUser=true");

                    // Map to PlayerUserData (mark as new user since we just created it)
                    return mapToPlayerUserData(response, uuid, true); // NEW user - just created
                });
            });
        });
    }
    
    /**
     * Map API response to PlayerUserData.
     */
    private PlayerUserData mapToPlayerUserData(UserResponseDto response, UUID uuid, boolean isNewUser) {
        return new PlayerUserData(
            response.id(),
            response.username(),
            uuid,
            response.email(),
            response.coins() != null ? response.coins() : 0,
            response.gems() != null ? response.gems() : 0,
            response.experiencePoints() != null ? response.experiencePoints() : 0,
            response.email() != null && !response.email().isBlank(),
            false,  // no duplicate if we got here
            null,
            GatePassThroughMethod.DEFAULT
        );
    }

    private PlayerUserData mapToPlayerUserData(UserSummary summary, UUID uuid, boolean isNewUser) {
        return new PlayerUserData(
            summary.id(),
            summary.username(),
            uuid,
            summary.email(),
            summary.coins(),
            summary.gems(),
            summary.experiencePoints(),
            summary.isFullAccount(),  // Use isFullAccount from API
            false,
            null,
            summary.gatePassThroughMethodDefault()
        );
    }
    
    /**
     * Get cached user data for a player.
     * 
     * @param uuid Player UUID
     * @return PlayerUserData if cached, null otherwise
     */
    public PlayerUserData getCachedUser(UUID uuid) {
        return userCache.get(uuid);
    }
    
    /**
     * Update cached user data (e.g., after account linking or merge).
     * 
     * @param uuid Player UUID
     * @param data Updated PlayerUserData
     */
    public void updateCachedUser(UUID uuid, PlayerUserData data) {
        userCache.put(uuid, data);
        logger.fine("Updated cache for UUID " + uuid);
    }
    
    /**
     * Remove a player from the cache (called on quit).
     * 
     * @param uuid Player UUID
     */
    public void clearCachedUser(UUID uuid) {
        PlayerUserData removed = userCache.remove(uuid);
        if (removed != null) {
            logger.fine("Cleared cache for user " + removed.username() + " (UUID: " + uuid + ")");
        }
    }
    
    /**
     * Get the account configuration.
     */
    public KnkConfig.AccountConfig getAccountConfig() {
        return accountConfig;
    }
    
    /**
     * Get the messages configuration.
     */
    public KnkConfig.MessagesConfig getMessagesConfig() {
        return messagesConfig;
    }
}
