package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.production.inbox.ClaimResult;
import net.knightsandkings.knk.core.production.inbox.DeliveryRequest;
import net.knightsandkings.knk.core.production.inbox.DeliveryResult;
import net.knightsandkings.knk.core.production.inbox.InboxView;

/**
 * Item delivery and the reward inbox, game server side (PandiO/knk-workspace#684; the contract of #60, knk-web-api
 * {@code ItemDeliveriesController} and {@code InboxController}). Failures carry the status code ({@code ApiException}):
 * 409 {@code ProductionDisabled} while the API's {@code Production:Enabled} is off, 409 {@code IdempotencyKeyReuse},
 * 400 for a bad line.
 */
public interface ItemDeliveryApi {

    /** {@code POST api/ItemDeliveries}: what the game server could not place goes to the player's inbox. */
    CompletableFuture<DeliveryResult> deliver(DeliveryRequest request);

    /** {@code GET api/Inbox/{userId}}. */
    CompletableFuture<InboxView> inbox(int userId);

    /**
     * {@code POST api/Inbox/{userId}/claim} as that player ({@code X-Acting-User-Id}): moves what the player has room for
     * out of the inbox, oldest first; the game server places exactly {@code accepted}.
     */
    CompletableFuture<ClaimResult> claim(int userId, String idempotencyKey, List<DeliveryRequest.Line> lines);
}
