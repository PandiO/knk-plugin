package net.knightsandkings.knk.core.domain.teleport;

/**
 * The answer to {@code POST api/teleport-destinations/refund}: whether anything was given back.
 * {@code refunded=false} means nothing had been charged under the key - the server then voids the
 * key so a charge still on its way can't take the player's money.
 *
 * @param newBalance the payer's balance afterwards; null when nothing was refunded
 * @param replayed   it had already been refunded (an earlier call, or staff)
 */
public record TeleportRefundResult(
    boolean refunded,
    String currency,
    long amount,
    Long newBalance,
    boolean replayed
) {
}
