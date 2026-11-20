package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.production.terminal.BindRequest;
import net.knightsandkings.knk.core.production.terminal.DisplaySample;
import net.knightsandkings.knk.core.production.terminal.StockMoveResult;
import net.knightsandkings.knk.core.production.terminal.StorageContainerInfo;
import net.knightsandkings.knk.core.production.terminal.TerminalPage;

/**
 * Storage terminals and display containers, game server side (PandiO/knk-workspace#74; the contract of #683, knk-web-api
 * {@code StorageContainersController}). Calls for a player send {@code X-Acting-User-Id}; {@code actingUserId} null is
 * the game server itself (a visitor for the reads). Failures carry the status code ({@code ApiException}): 403 not a
 * member of the storage's town or its player and not staff, 409 a display used as a terminal or {@code ProductionDisabled}.
 */
public interface StorageContainersApi {

    /** {@code GET api/StorageContainers/by-block}: the binding of a block, empty when it is not bound (404). */
    CompletableFuture<Optional<StorageContainerInfo>> byBlock(String world, int x, int y, int z);

    /** {@code POST api/StorageContainers/search} with {@code mode} and {@code worldName} filters (null = any); all pages. */
    CompletableFuture<List<StorageContainerInfo>> list(StorageContainerInfo.Mode mode, String worldName);

    /** {@code POST api/StorageContainers}: 409 when the block is already bound, 400 for a system storage or a double barrel. */
    CompletableFuture<StorageContainerInfo> bind(BindRequest request);

    /** {@code DELETE api/StorageContainers/{id}}. */
    CompletableFuture<Void> unbind(int containerId);

    /** {@code GET api/StorageContainers/{id}/terminal?page=&pageSize=} (page 1-based, at most 54 per page). */
    CompletableFuture<TerminalPage> terminal(int containerId, int page, int pageSize, Integer actingUserId);

    /** {@code POST api/StorageContainers/{id}/put}: a blueprint amount, or one unique item ({@code itemInstanceId}). */
    CompletableFuture<StockMoveResult> put(int containerId, int actingUserId, Integer itemBlueprintId, Long itemInstanceId,
                                           long amount, String idempotencyKey);

    /** {@code POST api/StorageContainers/{id}/take}. */
    CompletableFuture<StockMoveResult> take(int containerId, int actingUserId, Integer itemBlueprintId, Long itemInstanceId,
                                            long amount, String idempotencyKey);

    /** {@code GET api/StorageContainers/{id}/display}. */
    CompletableFuture<DisplaySample> display(int containerId);
}
