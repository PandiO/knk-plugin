package net.knightsandkings.knk.core.ports.api;

import java.util.concurrent.CompletableFuture;

/**
 * API port for user account management operations.
 * Handles account creation, linking, merging, and related operations.
 * Phase 1+ support for plugin-based account management.
 * 
 * Note: This interface uses generic Object return types to avoid circular dependencies.
 * Implementation in knk-api-client will handle concrete DTO mapping.
 */
public interface UserAccountApi {
    
    /**
     * Create a new user account.
     * 
     * @param request The user creation request (username, UUID, optional email/password)
     * @return Future with the created user response (includes ID, balance info)
     */
    CompletableFuture<Object> createUser(Object request);
    
    /**
     * Check for duplicate accounts (by UUID or username).
     * 
     * @param uuid The player UUID to check
     * @param username The player username to check
     * @return Future with duplicate check response (hasDuplicate, conflicting user info)
     */
    CompletableFuture<Object> checkDuplicate(String uuid, String username);
    
    /**
     * Generate a link code for existing account linking.
     * Used when player wants to link a web app account to their plugin account.
     * 
     * @param userId The user ID to generate code for
     * @return Future with link code response (code, expiry, formatted display)
     */
    CompletableFuture<Object> generateLinkCode(Integer userId);
    
    /**
     * Validate a link code before account linking.
     * 
     * @param code The link code to validate
     * @return Future with validation response (isValid, the code owner's username, error message if invalid).
     *         It carries no user id or email, and validating does not consume the code.
     */
    CompletableFuture<Object> validateLinkCode(String code);
    
    /**
     * Merge two accounts into one.
     * Primary account is kept; secondary is deleted.
     * 
     * @param request The merge request (primaryUserId, secondaryUserId)
     * @return Future with merged user response
     */
    CompletableFuture<Object> mergeAccounts(Object request);
    
    /**
     * Change user password.
     * 
     * @param userId The user ID
     * @param request The password change request
     * @return Future that completes when password is changed
     */
    CompletableFuture<Void> changePassword(Integer userId, Object request);
    
    /**
     * Update user email address.
     * 
     * @param userId The user ID
     * @param newEmail The new email address
     * @return Future that completes when email is updated
     */
    CompletableFuture<Void> updateEmail(Integer userId, String newEmail);
}
