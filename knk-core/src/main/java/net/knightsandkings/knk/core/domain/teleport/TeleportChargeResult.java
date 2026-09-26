package net.knightsandkings.knk.core.domain.teleport;

/**
 * The answer to a teleport charge (knk-web-api {@code POST api/teleport-destinations/{id}/charge} or
 * {@code .../request-fee}, docs/specs/teleport/DESIGN.md §3.7.3): either the teleport is paid for
 * (possibly nothing to pay) or the server refused it with a code and a player-facing message.
 * An unreachable server is not a result - the call completes exceptionally instead, and the
 * plugin retries with the same idempotency key.
 *
 * @param currency    "Gems" (warps) or "Coins" (requests)
 * @param charged     what this charge took; 0 for a free warp
 * @param newBalance  the payer's balance of {@code currency} afterwards
 * @param replayed    the key had already been charged - nothing more was taken
 * @param destination for a warp: the destination as the server has it now (authoritative location)
 */
public record TeleportChargeResult(
    boolean allowed,
    String currency,
    long charged,
    long newBalance,
    boolean replayed,
    KnkTeleportDestination destination,
    String refusalCode,
    String refusalMessage
) {
    /** The key's charge was refunded, or voided by a refund that came first. */
    public static final String REFUNDED = "Refunded";

    public static TeleportChargeResult allowed(String currency, long charged, long newBalance, boolean replayed,
                                               KnkTeleportDestination destination) {
        return new TeleportChargeResult(true, currency, charged, newBalance, replayed, destination, null, null);
    }

    public static TeleportChargeResult refused(String code, String message) {
        return new TeleportChargeResult(false, null, 0, 0, false, null,
            code != null ? code : "Refused", message != null ? message : "You can't teleport there right now.");
    }
}
