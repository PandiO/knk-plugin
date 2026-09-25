package net.knightsandkings.knk.core.domain.permissions;

import java.util.List;

/**
 * Full resolved permission set for one user, mirroring
 * GET /api/users/{id}/permissions/effective - every distinct node declared anywhere in the
 * user's resolution chain (their own direct grants, every group they belong to, and each
 * group's inheritance chain), each resolved to whichever holder wins for that exact node.
 */
public record EffectivePermissionSet(Integer userId, List<EffectivePermission> permissions) {
}
