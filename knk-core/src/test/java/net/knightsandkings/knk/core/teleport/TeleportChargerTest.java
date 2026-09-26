package net.knightsandkings.knk.core.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.teleport.TeleportChargeResult;
import net.knightsandkings.knk.core.domain.teleport.TeleportRefundResult;
import net.knightsandkings.knk.core.ports.api.TeleportDestinationsCommandApi;

/** Retry-safe teleport charges (DESIGN §3.7.3, §3.12): same key on retry, refund when unanswered. */
class TeleportChargerTest {

    /** A scripted API: each call takes the next outcome (null = no answer). */
    private static final class FakeApi implements TeleportDestinationsCommandApi {
        final List<String> chargeKeys = new ArrayList<>();
        final List<String> refundKeys = new ArrayList<>();
        final List<TeleportChargeResult> chargeOutcomes = new ArrayList<>();
        int refundFailures;

        @Override
        public CompletableFuture<TeleportChargeResult> chargeWarp(int domainId, int userId, String key,
                                                                 boolean bypassRequirements, boolean bypassCost) {
            chargeKeys.add(key);
            TeleportChargeResult next = chargeOutcomes.isEmpty() ? null : chargeOutcomes.remove(0);
            return next == null ? CompletableFuture.failedFuture(new RuntimeException("timeout")) : CompletableFuture.completedFuture(next);
        }

        @Override
        public CompletableFuture<TeleportChargeResult> chargeRequestFee(int userId, int amountCoins, String key, Integer otherUserId) {
            return chargeWarp(0, userId, key, false, false);
        }

        @Override
        public CompletableFuture<TeleportRefundResult> refund(int userId, String key, String reason) {
            refundKeys.add(key);
            if (refundFailures > 0) {
                refundFailures--;
                return CompletableFuture.failedFuture(new RuntimeException("down"));
            }
            return CompletableFuture.completedFuture(new TeleportRefundResult(true, "Gems", 10, 40L, false));
        }
    }

    private final FakeApi api = new FakeApi();
    private final TeleportCharger charger = new TeleportCharger(api, 3, Runnable::run, Runnable::run);

    private static TeleportChargeResult paid() {
        return TeleportChargeResult.allowed("Gems", 10, 40, false, null);
    }

    @Test
    void keysAreFreshPerAttemptAndPrefixed() {
        String a = TeleportCharger.newKey("warp");
        String b = TeleportCharger.newKey("warp");

        assertTrue(a.startsWith("warp:"));
        assertTrue(a.matches("[A-Za-z0-9:_.\\-]{1,100}"), "the API's key alphabet");
        assertFalse(a.equals(b));
    }

    @Test
    void aTimedOutChargeIsRetriedWithTheSameKey() {
        api.chargeOutcomes.add(null);
        api.chargeOutcomes.add(paid());

        TeleportChargeResult result = charger.chargeWarp(1, 7, "warp:k", false, false).join();

        assertTrue(result.allowed());
        assertEquals(List.of("warp:k", "warp:k"), api.chargeKeys);
        assertTrue(api.refundKeys.isEmpty());
    }

    @Test
    void aRefusalIsAnAnswer_NotRetried() {
        api.chargeOutcomes.add(TeleportChargeResult.refused("InsufficientGems", "Not enough gems"));

        TeleportChargeResult result = charger.chargeWarp(1, 7, "warp:k", false, false).join();

        assertFalse(result.allowed());
        assertEquals("InsufficientGems", result.refusalCode());
        assertEquals(1, api.chargeKeys.size());
        assertTrue(api.refundKeys.isEmpty());
    }

    @Test
    void noAnswerAfterEveryAttempt_RefundsTheKeyAndRefuses() {
        TeleportChargeResult result = charger.chargeRequestFee(7, 100, "tpa:k", 8).join();

        assertFalse(result.allowed());
        assertEquals(TeleportCharger.UNAVAILABLE, result.refusalCode());
        assertEquals(List.of("tpa:k", "tpa:k", "tpa:k"), api.chargeKeys);
        assertEquals(List.of("tpa:k"), api.refundKeys);
    }

    @Test
    void refundsAreRetriedUntilAnswered_AndNeverFailTheCaller() {
        api.refundFailures = 2;
        TeleportRefundResult answered = charger.refund(7, "warp:k", "blocked").join();
        assertTrue(answered.refunded());
        assertEquals(3, api.refundKeys.size());

        api.refundKeys.clear();
        api.refundFailures = 99;
        assertNull(charger.refund(7, "warp:k", "blocked").join());
        assertEquals(TeleportCharger.REFUND_ATTEMPTS, api.refundKeys.size());
    }
}
