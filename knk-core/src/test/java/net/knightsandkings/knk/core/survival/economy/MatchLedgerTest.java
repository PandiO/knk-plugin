package net.knightsandkings.knk.core.survival.economy;

import net.knightsandkings.knk.core.survival.economy.LedgerResult.Reason;
import net.knightsandkings.knk.core.survival.economy.MatchLedger.ChargeRequest;
import net.knightsandkings.knk.core.survival.economy.MatchLedger.TransferRequest;
import net.knightsandkings.knk.core.survival.mysterybox.MysteryBoxSettings;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S12, S33, S34, S44, S58, §8: balances from 0, atomic transfers, idempotent charges, ghosts excluded. */
class MatchLedgerTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private final MatchLedger ledger = new MatchLedger("match-1");

    private void join(UUID... players) {
        for (UUID p : players) ledger.join(p);
    }

    @Test
    void balancesStartAtZeroAndKillCurrencyIsImmediatelySpendable() {
        join(ALICE);
        assertEquals(0, ledger.balance(ALICE));
        assertTrue(ledger.credit(ALICE, 70));
        assertTrue(ledger.credit(ALICE, 77));
        assertEquals(147, ledger.balance(ALICE));
        assertFalse(ledger.credit(BOB, 70), "not a member");
        assertFalse(ledger.credit(ALICE, 0));
        assertEquals(0, ledger.balance(BOB));
    }

    @Test
    void ghostsEarnNothingAndCannotBuyTransferOrReceive() {
        join(ALICE, BOB);
        ledger.credit(ALICE, 500);
        ledger.credit(BOB, 500);
        ledger.setGhost(ALICE, true);
        assertFalse(ledger.credit(ALICE, 70));
        assertEquals(new LedgerResult.Rejected("t1", Reason.PAYER_IS_GHOST),
            ledger.transfer(new TransferRequest("t1", "match-1", ALICE, BOB, 100)));
        assertEquals(new LedgerResult.Rejected("t2", Reason.RECIPIENT_IS_GHOST),
            ledger.transfer(new TransferRequest("t2", "match-1", BOB, ALICE, 100)));
        assertEquals(new LedgerResult.Rejected("c1", Reason.PAYER_IS_GHOST),
            ledger.charge(new ChargeRequest("c1", "match-1", ALICE, 100, "sword")));
        assertFalse(ledger.canAfford(ALICE, 1));
        ledger.setGhost(ALICE, false);
        assertTrue(ledger.credit(ALICE, 70), "respawned: earns again");
        assertEquals(570, ledger.balance(ALICE));
    }

    @Test
    void transferIsAtomicPositiveSameMatchNoOverdraft() {
        join(ALICE, BOB);
        ledger.credit(ALICE, 300);
        LedgerResult ok = ledger.transfer(new TransferRequest("t1", "match-1", ALICE, BOB, 200));
        assertEquals(new LedgerResult.Accepted("t1", 100, false), ok);
        assertEquals(100, ledger.balance(ALICE));
        assertEquals(200, ledger.balance(BOB));

        assertEquals(Reason.INSUFFICIENT_BALANCE, reason(ledger.transfer(new TransferRequest("t2", "match-1", ALICE, BOB, 101))));
        assertEquals(100, ledger.balance(ALICE), "nothing moved on a rejected transfer");
        assertEquals(Reason.AMOUNT_NOT_POSITIVE, reason(ledger.transfer(new TransferRequest("t3", "match-1", ALICE, BOB, 0))));
        assertEquals(Reason.AMOUNT_NOT_POSITIVE, reason(ledger.transfer(new TransferRequest("t4", "match-1", ALICE, BOB, -5))));
        assertEquals(Reason.WRONG_MATCH, reason(ledger.transfer(new TransferRequest("t5", "match-2", ALICE, BOB, 10))));
        assertEquals(Reason.RECIPIENT_NOT_IN_MATCH, reason(ledger.transfer(new TransferRequest("t6", "match-1", ALICE, CAROL, 10))));
        assertEquals(Reason.PAYER_NOT_IN_MATCH, reason(ledger.transfer(new TransferRequest("t7", "match-1", CAROL, ALICE, 10))));
        assertEquals(Reason.SELF_TRANSFER, reason(ledger.transfer(new TransferRequest("t8", "match-1", ALICE, ALICE, 10))));
    }

    @Test
    void retriesAreIdempotentAndConflictingReuseIsRejected() {
        join(ALICE, BOB);
        ledger.credit(ALICE, 300);
        TransferRequest request = new TransferRequest("t1", "match-1", ALICE, BOB, 200);
        assertEquals(new LedgerResult.Accepted("t1", 100, false), ledger.transfer(request));
        assertEquals(new LedgerResult.Accepted("t1", 100, true), ledger.transfer(request), "replayed, not paid twice");
        assertEquals(100, ledger.balance(ALICE));
        assertEquals(200, ledger.balance(BOB));
        assertEquals(Reason.RETRY_ID_CONFLICT, reason(ledger.transfer(new TransferRequest("t1", "match-1", ALICE, BOB, 50))));
        assertEquals(Reason.RETRY_ID_CONFLICT, reason(ledger.charge(new ChargeRequest("t1", "match-1", ALICE, 50, "x"))));

        ChargeRequest rejected = new ChargeRequest("c1", "match-1", ALICE, 150, "sword");
        assertEquals(Reason.INSUFFICIENT_BALANCE, reason(ledger.charge(rejected)));
        ledger.credit(ALICE, 100);
        assertEquals(new LedgerResult.Accepted("c1", 50, false), ledger.charge(rejected), "a rejected request may be retried");
    }

    @Test
    void mysteryBoxRollChargesTheFixedPriceOncePerRollId() {
        join(ALICE);
        long price = MysteryBoxSettings.defaults().rollPrice();
        assertEquals(1000, price);
        ledger.credit(ALICE, 999);
        assertFalse(ledger.canAfford(ALICE, price));
        assertEquals(Reason.INSUFFICIENT_BALANCE, reason(ledger.charge(new ChargeRequest("roll-1", "match-1", ALICE, price, "mystery-box"))));
        ledger.credit(ALICE, 1);
        assertTrue(ledger.canAfford(ALICE, price));
        assertEquals(new LedgerResult.Accepted("roll-1", 0, false), ledger.charge(new ChargeRequest("roll-1", "match-1", ALICE, price, "mystery-box")));
        assertEquals(new LedgerResult.Accepted("roll-1", 0, true), ledger.charge(new ChargeRequest("roll-1", "match-1", ALICE, price, "mystery-box")));
        assertEquals(0, ledger.balance(ALICE));
    }

    @Test
    void leavingDropsTheBalanceAndAnEndedMatchRejectsEverything() {
        join(ALICE, BOB);
        ledger.credit(ALICE, 100);
        ledger.leave(ALICE);
        assertFalse(ledger.isMember(ALICE));
        assertEquals(0, ledger.balance(ALICE));
        ledger.join(ALICE);
        assertEquals(0, ledger.balance(ALICE), "a new join starts at 0");
        ledger.credit(BOB, 100);
        ledger.end();
        assertFalse(ledger.credit(BOB, 1));
        assertEquals(Reason.MATCH_ENDED, reason(ledger.transfer(new TransferRequest("t", "match-1", BOB, ALICE, 1))));
        assertEquals(Reason.MATCH_ENDED, reason(ledger.charge(new ChargeRequest("c", "match-1", BOB, 1, "x"))));
        assertEquals(100, ledger.balances().get(BOB), "result screen still reads the final balance");
    }

    private static Reason reason(LedgerResult result) {
        assertFalse(result.accepted(), result.toString());
        return ((LedgerResult.Rejected) result).reason();
    }
}
