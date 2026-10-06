package net.knightsandkings.knk.core.domain.teleport;

import java.util.List;

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
 * @param payments    every currency taken (Linear KNG-41: a group's fixed price may combine coins, gems
 *                    and XP); empty when nothing was charged. {@code currency/charged/newBalance} are
 *                    the first of them.
 */
public record TeleportChargeResult(
    boolean allowed,
    String currency,
    long charged,
    long newBalance,
    boolean replayed,
    KnkTeleportDestination destination,
    String refusalCode,
    String refusalMessage,
    List<TeleportPayment> payments
) {
    public TeleportChargeResult {
        payments = payments != null ? List.copyOf(payments) : List.of();
    }

    /** A result without a payment list: derived from {@code currency/charged/newBalance}. */
    public TeleportChargeResult(boolean allowed, String currency, long charged, long newBalance, boolean replayed,
                                KnkTeleportDestination destination, String refusalCode, String refusalMessage) {
        this(allowed, currency, charged, newBalance, replayed, destination, refusalCode, refusalMessage,
            allowed && charged > 0 && currency != null ? List.of(new TeleportPayment(currency, charged, newBalance)) : List.of());
    }

    /** The key's charge was refunded, or voided by a refund that came first. */
    public static final String REFUNDED = "Refunded";

    public static TeleportChargeResult allowed(String currency, long charged, long newBalance, boolean replayed,
                                               KnkTeleportDestination destination) {
        return new TeleportChargeResult(true, currency, charged, newBalance, replayed, destination, null, null);
    }

    /** An allowed charge that took {@code payments} (possibly none); the first sets {@code currency/charged/newBalance}. */
    public static TeleportChargeResult allowed(String currency, List<TeleportPayment> payments, long newBalance,
                                               boolean replayed, KnkTeleportDestination destination) {
        if (payments == null || payments.isEmpty()) {
            return new TeleportChargeResult(true, currency, 0, newBalance, replayed, destination, null, null, List.of());
        }
        TeleportPayment first = payments.get(0);
        return new TeleportChargeResult(true, first.currency(), first.amount(), first.newBalance(), replayed, destination,
            null, null, payments);
    }

    /** True when the charge took anything. */
    public boolean paid() {
        return charged > 0 || payments.stream().anyMatch(p -> p.amount() > 0);
    }

    public static TeleportChargeResult refused(String code, String message) {
        return new TeleportChargeResult(false, null, 0, 0, false, null,
            code != null ? code : "Refused", message != null ? message : "You can't teleport there right now.", List.of());
    }
}
