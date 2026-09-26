package net.knightsandkings.knk.paper.user;

import net.knightsandkings.knk.core.domain.users.SalaryPayoutResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalaryPayoutSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    private static SalaryPayoutResult paid(int amount, double hours, OffsetDateTime nextEligibleAt) {
        return new SalaryPayoutResult(true, amount, hours, 1.0, 1.0, 1.0, amount, null, nextEligibleAt, 0, 650);
    }

    @Test
    void unknownOrPassedNextCheckIsDue() {
        assertTrue(SalaryPayoutScheduler.isDue(null, NOW));
        assertTrue(SalaryPayoutScheduler.isDue(NOW, NOW));
        assertTrue(SalaryPayoutScheduler.isDue(NOW.minusSeconds(1), NOW));
        assertFalse(SalaryPayoutScheduler.isDue(NOW.plusSeconds(1), NOW));
    }

    @Test
    void nextCheckFollowsTheApisNextEligibleTime() {
        OffsetDateTime eligible = OffsetDateTime.of(2026, 9, 26, 12, 59, 0, 0, ZoneOffset.UTC);

        assertEquals(eligible.toInstant(), SalaryPayoutScheduler.nextCheck(paid(650, 1.0, eligible), NOW));
    }

    @Test
    void nextCheckAfterAFailedCallWaitsTheRetryDelay() {
        assertEquals(NOW.plus(SalaryPayoutScheduler.RETRY_DELAY), SalaryPayoutScheduler.nextCheck(null, NOW));
    }

    @Test
    void nextCheckWithoutNextEligibleTimeWaitsAnHour() {
        assertEquals(NOW.plus(SalaryPayoutScheduler.PAYOUT_INTERVAL), SalaryPayoutScheduler.nextCheck(paid(650, 1.0, null), NOW));
    }

    @Test
    void hourlyPayoutMessageShowsJustTheAmount() {
        assertEquals("You received 650 coins in salary.", SalaryPayoutScheduler.payoutMessage(paid(650, 1.01, null)));
    }

    @Test
    void gapPayoutMessageShowsTheHoursCovered() {
        assertEquals("You received 3250 coins in salary for the past 5.0 hours.",
                SalaryPayoutScheduler.payoutMessage(paid(3250, 5.0, null)));
    }
}
