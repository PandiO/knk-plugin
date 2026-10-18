package net.knightsandkings.knk.core.domain.permissions;

/**
 * One entry in a user's full resolved permission set (see {@link EffectivePermissionSet}) -
 * a single declared node, its resolved value, and which holder it came from.
 *
 * @param rank the source holder's place in the API's resolution chain (0 = the user's own grants,
 *             then the groups in precedence order); null from an API that doesn't send it, which
 *             leaves the set unusable for {@link EffectivePermissionResolver} (KNG-107)
 */
public record EffectivePermission(String node, boolean value, PermissionHolder source, Integer rank) {

    public EffectivePermission(String node, boolean value, PermissionHolder source) {
        this(node, value, source, null);
    }
}
