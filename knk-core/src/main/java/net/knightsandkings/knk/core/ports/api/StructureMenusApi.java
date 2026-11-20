package net.knightsandkings.knk.core.ports.api;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.production.menu.StructureMenu;
import net.knightsandkings.knk.core.production.menu.StructureRow;
import net.knightsandkings.knk.core.production.menu.TransportDryRun;
import net.knightsandkings.knk.core.production.menu.TransportOrderRow;

/**
 * The in-game structure menus and their staff buttons (PandiO/knk-workspace#74; the contract of #683, knk-web-api
 * {@code StructureMenusController}, {@code TransportOrdersController}, {@code ProductionStructuresController}). Reads send
 * {@code X-Acting-User-Id} for a player so the API filters by caller (M3); null is a visitor. Failures carry the status
 * code ({@code ApiException}): 403 for the orders of a visitor and for a staff action of a non-staff player.
 */
public interface StructureMenusApi {

    /** {@code POST api/StructureMenus/search}: filters {@code townId}, {@code productionKindId}, {@code structureType}, {@code status}. */
    CompletableFuture<Page<StructureRow>> search(PagedQuery query, Integer actingUserId);

    /** {@code GET api/StructureMenus/{id}}; empty for an unknown structure (404). */
    CompletableFuture<Optional<StructureMenu>> get(int structureId, Integer actingUserId);

    /** {@code GET api/StructureMenus/{id}/orders?page=&pageSize=} (page 1-based), newest first. */
    CompletableFuture<Page<TransportOrderRow>> orders(int structureId, int page, int pageSize, Integer actingUserId);

    /** {@code POST api/TransportOrders/{id}/cancel}. */
    CompletableFuture<Void> cancelOrder(int orderId, int actingUserId);

    /** {@code POST api/TransportOrders/{id}/retry}. */
    CompletableFuture<Void> retryOrder(int orderId, int actingUserId);

    /** {@code GET api/ProductionStructures/{id}/transport}: the dry run; empty for an unknown structure. */
    CompletableFuture<Optional<TransportDryRun>> transportDryRun(int structureId, Integer actingUserId);
}
