package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * knk-web-api's ItemInstances write side for the game server (PandiO/knk-workspace#175): {@code /knk item more} gets
 * a new ItemInstance per copy of an item carrying {@code knightsandkings:knk_item_instance}, so no two items share an
 * id (two items with one id are a provable dupe, lootbox DESIGN §3.2).
 */
public interface ItemInstancesApi {

    /**
     * {@code POST api/ItemInstances/{sourceId}/clone}: mints {@code count} (1..64) new instances copied from
     * {@code sourceId} and answers their ids in mint order.
     *
     * @param ownerUserId the player receiving the copies (their knk user id); null keeps the source's owner
     * @param actorUserId the staff member, sent as {@code X-Acting-User-Id} for the audit entry; null = system
     */
    CompletableFuture<List<Long>> cloneAsync(long sourceId, int count, Integer ownerUserId, Integer actorUserId);
}
