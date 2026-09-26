package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * InventoryMenu Phase 9 (E3): a content source that returns plain row objects
 * (view records) instead of Java-mapped {@link RuntimeMenuItem}s. Each row is
 * rendered through its section's row template ({@code IsRowTemplate}) with the
 * getter-chain root {@code $row$} bound to it. Registered with a declared row
 * type via {@link MenuContentSourceRegistry#registerRows}, which is what lets
 * {@link MenuDefinitionValidator} check {@code $row.*$} chains at enable.
 * <p>
 * {@code params} are the section's {@code ContentSourceParamsJson} values with
 * their {@code $…$} placeholders already resolved for this render (e.g.
 * {@code {"lobbyId": "$ctx.lobbyId$"}} arrives as {@code {"lobbyId": "3"}}).
 * <p>
 * Called on the server main thread (IMPLEMENTATION_PLAN.md Phase 9 §9.0); an
 * in-memory source returns {@code CompletableFuture.completedFuture(page)}, a
 * source doing I/O returns a future that completes elsewhere and the engine
 * waits for it off the main thread. Sources that don't page can ignore
 * {@code query} and return everything - the engine slices the result to the
 * page (see {@code MenuRenderer}).
 */
@FunctionalInterface
public interface MenuRowSource<C, R> {
    CompletableFuture<Page<R>> fetchRows(C context, Map<String, String> params, PagedQuery query);
}
