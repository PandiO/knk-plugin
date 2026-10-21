package net.knightsandkings.knk.core.duel;

import net.knightsandkings.knk.core.duel.DuelEscrowPlan.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuelEscrowTest {

    @Test
    void stateTransitions() {
        assertTrue(DuelEscrowState.LOCKING.canMoveTo(DuelEscrowState.ESCROWED));
        assertTrue(DuelEscrowState.LOCKING.canMoveTo(DuelEscrowState.REFUNDED));
        assertFalse(DuelEscrowState.LOCKING.canMoveTo(DuelEscrowState.SETTLED));
        assertTrue(DuelEscrowState.ESCROWED.canMoveTo(DuelEscrowState.HELD));
        assertTrue(DuelEscrowState.HELD.canMoveTo(DuelEscrowState.SETTLED));
        assertTrue(DuelEscrowState.HELD.canMoveTo(DuelEscrowState.REFUNDED));
        assertFalse(DuelEscrowState.HELD.canMoveTo(DuelEscrowState.ESCROWED));
        assertTrue(DuelEscrowState.SETTLED.canMoveTo(DuelEscrowState.CLAIM_PENDING));
        assertFalse(DuelEscrowState.SETTLED.canMoveTo(DuelEscrowState.REFUNDED), "settled once");
        assertTrue(DuelEscrowState.CLAIM_PENDING.allowedNext().isEmpty());
        assertTrue(DuelEscrowState.REFUNDED.isFinal());
        assertFalse(DuelEscrowState.HELD.isFinal());
    }

    @Test
    void aPaidWinGivesEverythingToTheWinner() {
        DuelResult win = DuelResult.win(DuelSide.OPPONENT, DuelEndReason.WIN);
        DuelEscrowPlan plan = DuelEscrowPlan.forResult(win, Decision.PAID, DuelSide.CHALLENGER);
        assertEquals(new DuelEscrowPlan(DuelSide.OPPONENT, DuelSide.OPPONENT, DuelSide.OPPONENT, DuelEscrowState.SETTLED), plan);
    }

    @Test
    void aHeldWinKeepsEverythingIncludingSpoils() {
        DuelResult win = DuelResult.win(DuelSide.CHALLENGER, DuelEndReason.FORFEIT);
        DuelEscrowPlan plan = DuelEscrowPlan.forResult(win, Decision.HELD, DuelSide.OPPONENT);
        assertTrue(plan.keepsEverything());
        assertEquals(DuelEscrowState.HELD, plan.next());
    }

    @Test
    void aVoidRefundsAndReturnsSpoilsToTheLoser() {
        DuelResult win = DuelResult.win(DuelSide.CHALLENGER, DuelEndReason.WIN);
        DuelEscrowPlan plan = DuelEscrowPlan.forResult(win, Decision.VOIDED, DuelSide.OPPONENT);
        assertEquals(DuelSide.CHALLENGER, plan.challengerStakeTo());
        assertEquals(DuelSide.OPPONENT, plan.opponentStakeTo());
        assertEquals(DuelSide.OPPONENT, plan.spoilsTo());
        assertEquals(DuelEscrowState.REFUNDED, plan.next());
    }

    @Test
    void everyNonDecisiveResultRefundsWhateverTheApiSaid() {
        for (DuelEndReason reason : DuelEndReason.values()) {
            if (reason.isDecisive()) continue;
            DuelEscrowPlan plan = DuelEscrowPlan.forResult(DuelResult.noWinner(reason), Decision.PAID, null);
            assertEquals(DuelEscrowState.REFUNDED, plan.next(), reason.name());
            assertEquals(DuelSide.CHALLENGER, plan.challengerStakeTo());
            assertNull(plan.spoilsTo());
        }
    }

    @Test
    void recovery() {
        assertEquals(DuelEscrowState.SETTLED,
                DuelEscrowPlan.forRecovery("Completed", DuelSide.OPPONENT, false, DuelSide.CHALLENGER).next());
        assertEquals(DuelEscrowState.HELD, DuelEscrowPlan.forRecovery("Completed", DuelSide.OPPONENT, true, null).next());
        assertEquals(DuelEscrowState.REFUNDED, DuelEscrowPlan.forRecovery("Aborted", null, false, null).next());
        assertEquals(DuelEscrowState.REFUNDED, DuelEscrowPlan.forRecovery(null, null, false, null).next(), "404 in LOCKING");
        assertEquals(DuelEscrowState.REFUNDED, DuelEscrowPlan.forRecovery("Completed", null, false, null).next(), "a draw");
    }

    @Test
    void endReasonNamesMatchTheApi() {
        assertEquals("SimultaneousDeath", DuelEndReason.SIMULTANEOUS_DEATH.apiName());
        assertEquals("Win", DuelEndReason.WIN.apiName());
        assertEquals("ApiOutage", DuelEndReason.API_OUTAGE.apiName());
        assertNull(DuelEndReason.LOCK_REFUSED.apiName());
        assertTrue(DuelEndReason.TIME_LIMIT.isDraw());
        assertFalse(DuelEndReason.NO_BATTLEGROUND.isDraw());
        assertEquals(DuelEndReason.Status.ABORTED, DuelEndReason.SERVER_RESTART.status());
    }
}
