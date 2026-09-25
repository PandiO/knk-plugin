package net.knightsandkings.knk.core.menu;

/**
 * InventoryMenu Phase 9 (E3), optional: a row object a content source returns
 * may implement this to give the variable cache a stable identity - e.g. a
 * siege lobby row returning its lobby id. Without it, a row's identity is the
 * row object itself compared with {@code equals}, which for an immutable view
 * record means "any changed field makes every binding on that row re-resolve"
 * - correct, just less caching. With it, {@code Static}/{@code OnDirty}/{@code Ttl}
 * policies on row-template bindings behave exactly like on ordinary items as
 * long as the same logical row stays at the same position.
 */
public interface MenuRowKey {
    Object menuRowKey();
}
