package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;

import java.util.concurrent.CompletableFuture;

/**
 * IMPLEMENTATION_PLAN.md Phase 8: a code-side source of dynamic,
 * paged/cursor content for a {@code contentSourceId}-bound
 * {@link RuntimeMenuSection}, replacing the section's persisted
 * {@code items} list as the source of its auto-placed (non-pinned) content.
 * Generic over the execution context type {@code C}, mirroring
 * {@link ActionRegistry}/{@link ConditionRegistry}'s established split: the
 * registry mechanism itself (String-keyed map, lookup, "unregistered id"
 * failure mode) stays Bukkit-free here in knk-core, while knk-paper
 * instantiates {@code MenuContentSourceRegistry<MenuContentSourceContext>}
 * and registers real, Bukkit/REST-backed handlers (e.g. one backed by
 * {@code ItemBlueprintsDataAccess}) at plugin enable.
 * <p>
 * A handler is expected to issue a real paged/cursor query against its
 * backing store for every call (no full-list preload) - the caller
 * (knk-paper's {@code MenuRenderer}) supplies a {@link PagedQuery} sized to
 * the section's actual available-slot capacity, with the session's active
 * {@link MenuContentQuery} threaded through as {@code searchTerm}/
 * {@code filters} so search/filter narrow the full backing set, not just
 * the current page (see {@code MenuContentSourceHandlers}' javadoc for why
 * this differs from the legacy in-memory {@code buildContentFilter} path).
 */
@FunctionalInterface
public interface MenuContentSource<C> {
    CompletableFuture<Page<RuntimeMenuItem>> fetchPage(C context, PagedQuery query);
}
