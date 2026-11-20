package net.knightsandkings.knk.core.siege.economy;

import net.knightsandkings.knk.core.siege.economy.SiegePointsEarnCalculator.Award;
import net.knightsandkings.knk.core.siege.economy.SiegePointsEconomy.Credit;
import net.knightsandkings.knk.core.siege.economy.SiegePointsEconomy.TransferOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One PvP match's siege points on the shared ledger (siege shop § 5.1, P07, P12, P15, P27; PandiO/knk-workspace#866):
 * A01 no overdraft, A02 retries, A03 the transfer switch, A04 balances from 0 and gone after the match, A11 totals.
 */
class SiegePointsEconomyTest {

    private static final UUID A1 = new UUID(0, 1);
    private static final UUID A2 = new UUID(0, 2);
    private static final UUID D1 = new UUID(0, 11);

    private SiegePointsEconomy economy(boolean transfers) {
        SiegePointsEconomy e = new SiegePointsEconomy("match-1",
                SiegePointsSettings.defaults().withTransfersEnabled(transfers));
        e.join(A1);
        e.join(A2);
        e.join(D1);
        return e;
    }

    @Test
    void everyMemberStartsAtZero_A04() {
        SiegePointsEconomy e = economy(false);
        assertEquals(Map.of(A1, 0L, A2, 0L, D1, 0L), e.ledger().balances());
        assertEquals(0, e.earned(A1));
        assertEquals(0, e.spent(A1));
    }

    @Test
    void creditsReachTheBalanceAndTheEarnedTotal() {
        SiegePointsEconomy e = economy(false);
        List<Credit> credits = e.apply(List.of(new Award(A1, SiegePointsSource.KILL, 110, 1.1),
                new Award(D1, SiegePointsSource.DEATH, 15, 1.0), new Award(new UUID(9, 9), SiegePointsSource.ASSIST, 40, 1.0)));
        assertEquals(List.of(new Credit(A1, SiegePointsSource.KILL, 110, 1.1, 110),
                new Credit(D1, SiegePointsSource.DEATH, 15, 1.0, 15)), credits, "a non-member gets nothing");
        assertEquals(110, e.earned(A1));
    }

    @Test
    void notableEventsUseTheSameCreditHook_P15() {
        SiegePointsEconomy e = economy(false);
        Optional<Credit> credit = e.credit(A2, SiegePointsSource.NOTABLE_EVENT, 250);
        assertEquals(Optional.of(new Credit(A2, SiegePointsSource.NOTABLE_EVENT, 250, 1.0, 250)), credit);
        assertEquals(Optional.empty(), e.credit(A2, SiegePointsSource.NOTABLE_EVENT, 0));
    }

    @Test
    void transfersAreRefusedWhileTheModesSwitchIsOff_A03() {
        SiegePointsEconomy e = economy(false);
        e.credit(A1, SiegePointsSource.KILL, 100);
        TransferOutcome outcome = e.transfer("t1", A1, A2, 50, true);
        assertEquals(TransferOutcome.Status.TRANSFERS_OFF, outcome.status());
        assertFalse(outcome.accepted());
        assertEquals(100, e.balance(A1));
        assertEquals(0, e.balance(A2));
    }

    @Test
    void transfersWorkWithTheSwitchOnBetweenTeammatesOnlyAndNeverOverdraw_A01_A03() {
        SiegePointsEconomy e = economy(true);
        e.credit(A1, SiegePointsSource.KILL, 100);
        TransferOutcome ok = e.transfer("t1", A1, A2, 50, true);
        assertTrue(ok.accepted());
        assertEquals(50, e.balance(A1));
        assertEquals(50, e.balance(A2));
        assertEquals(50, e.spent(A1));
        assertEquals(50, e.earned(A2));

        TransferOutcome tooMuch = e.transfer("t2", A1, A2, 999_999, true);
        assertFalse(tooMuch.accepted());
        assertEquals(LedgerResult.Reason.INSUFFICIENT_BALANCE, ((LedgerResult.Rejected) tooMuch.ledgerResult()).reason());
        assertEquals(50, e.balance(A1), "nothing changed");

        assertEquals(TransferOutcome.Status.NOT_TEAMMATE, e.transfer("t3", A1, D1, 10, false).status());
        assertEquals(0, e.balance(D1));
    }

    @Test
    void aRetriedTransferOrChargeIsAppliedOnce_A02() {
        SiegePointsEconomy e = economy(true);
        e.credit(A1, SiegePointsSource.KILL, 300);
        assertTrue(e.transfer("t1", A1, A2, 100, true).accepted());
        TransferOutcome again = e.transfer("t1", A1, A2, 100, true);
        assertTrue(((LedgerResult.Accepted) again.ledgerResult()).replayed());
        assertEquals(200, e.balance(A1));
        assertEquals(100, e.spent(A1), "counted once");

        assertTrue(e.charge("shop:1", A1, 150, "ender_pearl").accepted());
        assertTrue(((LedgerResult.Accepted) e.charge("shop:1", A1, 150, "ender_pearl")).replayed());
        assertEquals(50, e.balance(A1));
        assertEquals(250, e.spent(A1));
        assertEquals(LedgerResult.Reason.INSUFFICIENT_BALANCE,
                ((LedgerResult.Rejected) e.charge("shop:2", A1, 51, "x")).reason(), "A01: above the balance");
        assertEquals(50, e.balance(A1));
    }

    @Test
    void aLeaverLosesTheBalanceButKeepsTheTotalsAndTheMatchEndStopsEverything_A04() {
        SiegePointsEconomy e = economy(true);
        e.credit(A1, SiegePointsSource.KILL, 100);
        e.leave(A1);
        assertFalse(e.isMember(A1));
        assertEquals(0, e.balance(A1));
        assertEquals(100, e.earnedTotals().get(A1), "the result still reports what they earned");

        e.credit(A2, SiegePointsSource.KILL, 100);
        e.end();
        assertTrue(e.ended());
        assertEquals(Optional.empty(), e.credit(A2, SiegePointsSource.KILL, 100));
        assertFalse(e.transfer("t", A2, D1, 1, true).accepted());
        assertEquals(100, e.balance(A2), "readable for the result after the end");

        SiegePointsEconomy next = new SiegePointsEconomy("match-2", SiegePointsSettings.defaults());
        next.join(A2);
        assertEquals(0, next.balance(A2), "the next match starts at 0");
    }
}
