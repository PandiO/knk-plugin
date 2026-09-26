package net.knightsandkings.knk.core.teleport;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.domain.teleport.TeleportChargeResult;
import net.knightsandkings.knk.core.domain.teleport.TeleportRefundResult;
import net.knightsandkings.knk.core.ports.api.TeleportDestinationsCommandApi;

/**
 * Makes teleport charges safe to retry (docs/specs/teleport/DESIGN.md §3.7.3, §3.12): one
 * idempotency key per teleport attempt ({@link #newKey}), and
 * <ul>
 *   <li>a charge that gets no answer (network error, timeout, 5xx) is sent again with the <em>same</em>
 *       key - the server charges a key at most once - up to {@code attempts} times;</li>
 *   <li>when it never gets an answer, the teleport doesn't happen and a refund is sent for the key:
 *       it gives back a charge that did go through, or voids the key so a late one can't;</li>
 *   <li>refunds are retried with the same key until answered (up to {@value #REFUND_ATTEMPTS}
 *       times) and never fail the caller; a refund that can't be delivered is logged as a WARNING
 *       with the user and key so staff can reverse it by hand.</li>
 * </ul>
 * A refusal (the server said no) is an answer: it is returned as is, never retried.
 */
public class TeleportCharger {

    private static final Logger LOGGER = Logger.getLogger(TeleportCharger.class.getName());
    public static final int DEFAULT_CHARGE_ATTEMPTS = 3;
    static final int REFUND_ATTEMPTS = 5;
    public static final String UNAVAILABLE = "Unavailable";
    static final String UNAVAILABLE_MESSAGE = "Teleporting isn't available right now - nothing was charged. Try again.";

    private final TeleportDestinationsCommandApi api;
    private final int chargeAttempts;
    private final Executor chargeRetryExecutor;
    private final Executor refundRetryExecutor;

    public TeleportCharger(TeleportDestinationsCommandApi api) {
        this(api, DEFAULT_CHARGE_ATTEMPTS,
            CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS),
            CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS));
    }

    /**
     * @param chargeRetryExecutor runs each charge retry (the default waits 1 s first)
     * @param refundRetryExecutor runs each refund retry (the default waits 5 s first)
     */
    public TeleportCharger(TeleportDestinationsCommandApi api, int chargeAttempts, Executor chargeRetryExecutor,
                           Executor refundRetryExecutor) {
        this.api = Objects.requireNonNull(api, "api must not be null");
        this.chargeAttempts = Math.max(1, chargeAttempts);
        this.chargeRetryExecutor = Objects.requireNonNull(chargeRetryExecutor, "chargeRetryExecutor must not be null");
        this.refundRetryExecutor = Objects.requireNonNull(refundRetryExecutor, "refundRetryExecutor must not be null");
    }

    /** A fresh key for one teleport attempt, e.g. {@code warp:3f2a...}. */
    public static String newKey(String prefix) {
        return prefix + ":" + UUID.randomUUID();
    }

    public CompletableFuture<TeleportChargeResult> chargeWarp(int domainId, int userId, String key,
                                                             boolean bypassRequirements, boolean bypassCost) {
        return charge(userId, key, () -> api.chargeWarp(domainId, userId, key, bypassRequirements, bypassCost));
    }

    public CompletableFuture<TeleportChargeResult> chargeRequestFee(int userId, int amountCoins, String key, Integer otherUserId) {
        return charge(userId, key, () -> api.chargeRequestFee(userId, amountCoins, key, otherUserId));
    }

    /**
     * Give back the charge made under {@code key}. Never completes exceptionally: completes with the
     * server's answer, or with null once every attempt failed (logged).
     */
    public CompletableFuture<TeleportRefundResult> refund(int userId, String key, String reason) {
        return refundAttempt(userId, key, reason, 1);
    }

    /** Never completes exceptionally: no answer after every attempt becomes a refusal, after a refund of the key. */
    private CompletableFuture<TeleportChargeResult> charge(int userId, String key,
                                                          Supplier<CompletableFuture<TeleportChargeResult>> call) {
        return chargeAttempt(call, 1).handle((result, ex) -> {
            if (ex == null && result != null) {
                return CompletableFuture.completedFuture(result);
            }
            LOGGER.log(Level.WARNING, "[KnK Teleport] Charge " + key + " for user " + userId + " got no answer after "
                + chargeAttempts + " attempts - refunding the key", ex);
            refund(userId, key, "the charge got no answer");
            return CompletableFuture.completedFuture(TeleportChargeResult.refused(UNAVAILABLE, UNAVAILABLE_MESSAGE));
        }).thenCompose(future -> future);
    }

    private CompletableFuture<TeleportChargeResult> chargeAttempt(Supplier<CompletableFuture<TeleportChargeResult>> call, int attempt) {
        CompletableFuture<TeleportChargeResult> future;
        try {
            future = call.get();
        } catch (RuntimeException ex) {
            future = CompletableFuture.failedFuture(ex);
        }
        if (attempt >= chargeAttempts) {
            return future;
        }
        return future.exceptionallyComposeAsync(ex -> {
            LOGGER.log(Level.FINE, "Teleport charge attempt " + attempt + " failed, retrying with the same key", ex);
            return chargeAttempt(call, attempt + 1);
        }, chargeRetryExecutor);
    }

    private CompletableFuture<TeleportRefundResult> refundAttempt(int userId, String key, String reason, int attempt) {
        CompletableFuture<TeleportRefundResult> future;
        try {
            future = api.refund(userId, key, reason);
        } catch (RuntimeException ex) {
            future = CompletableFuture.failedFuture(ex);
        }
        return future.exceptionallyComposeAsync(ex -> {
            if (attempt >= REFUND_ATTEMPTS) {
                LOGGER.log(Level.WARNING, "[KnK Teleport] Could not refund teleport charge " + key + " of user " + userId
                    + " after " + attempt + " attempts - reverse it by hand if it was charged", ex);
                return CompletableFuture.completedFuture(null);
            }
            return refundAttempt(userId, key, reason, attempt + 1);
        }, refundRetryExecutor);
    }
}
