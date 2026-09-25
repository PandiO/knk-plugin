package net.knightsandkings.knk.core.ports.api;

import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.permissions.EffectivePermissionSet;
import net.knightsandkings.knk.core.domain.permissions.PermissionCheckResult;

/**
 * API port for the user-features permission system (docs/specs/user-features/DESIGN.md §2.2).
 * Backed by knk-web-api's GET /api/users/{id}/permissions/check and .../permissions/effective.
 */
public interface PermissionsApi {

    /**
     * Cheap single-node permission check - this is what call sites hit on every
     * hasPermission-equivalent check, so it should stay a single, direct lookup.
     *
     * @param userId The user to check.
     * @param node   The permission node to check, e.g. "knk.gate.open".
     * @return Future with the resolution result, or null if the user doesn't exist.
     */
    CompletableFuture<PermissionCheckResult> check(int userId, String node);

    /**
     * The user's full resolved permission set - every declared node, its value, and which
     * holder it came from.
     *
     * @param userId The user to resolve.
     * @return Future with the effective set, or null if the user doesn't exist.
     */
    CompletableFuture<EffectivePermissionSet> getEffective(int userId);
}
