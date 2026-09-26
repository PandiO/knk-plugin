package net.knightsandkings.knk.core.ports.api;

import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.teleport.TeleportChargeResult;
import net.knightsandkings.knk.core.domain.teleport.TeleportRefundResult;

/**
 * Teleport charges (knk-web-api TeleportDestinationsController, docs/specs/teleport/DESIGN.md
 * §3.7.3; ledger reason TELEPORT_FEE). Every call carries an idempotency key the plugin makes once
 * per teleport attempt, so a call that timed out can be repeated safely: the server charges a key
 * at most once and refunds it at most once.
 * <p>
 * A refusal (409 and other 4xx) completes normally with {@link TeleportChargeResult#allowed()} false;
 * only a failure to get an answer (network, 5xx) completes exceptionally.
 */
public interface TeleportDestinationsCommandApi {

    /**
     * Authorize a warp to {@code domainId} for the player and charge its gem price. Called after the
     * warmup for every warp, free ones too - the server re-checks title, premium tier and discovery.
     *
     * @param bypassRequirements the player holds {@code knk.teleport.bypass.requirements}
     * @param bypassCost         the player holds {@code knk.teleport.bypass.cost} (nothing is charged)
     */
    CompletableFuture<TeleportChargeResult> chargeWarp(int domainId, int userId, String idempotencyKey,
                                                      boolean bypassRequirements, boolean bypassCost);

    /** Charge the coin fee of a {@code /tpa} or {@code /tpahere} to the requester. */
    CompletableFuture<TeleportChargeResult> chargeRequestFee(int userId, int amountCoins, String idempotencyKey,
                                                            Integer otherUserId);

    /** Give back what the charge made under {@code idempotencyKey} took; harmless to repeat. */
    CompletableFuture<TeleportRefundResult> refund(int userId, String idempotencyKey, String reason);
}
