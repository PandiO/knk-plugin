package net.knightsandkings.knk.core.survival.economy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchEconomyTest {

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final MatchEconomy economy = new MatchEconomy("match-1", KillRewardSettings.defaults());

    MatchEconomyTest() {
        economy.join(alice);
        economy.join(bob);
    }

    @Test
    void aPlainKillPays70AndAccruesExactPermanentRewards() {
        Optional<MatchEconomy.KillCredit> credit = economy.creditKill(1, new KillBlow(alice, false, false));
        assertTrue(credit.isPresent());
        assertEquals(70, credit.get().reward().matchCurrency());
        assertEquals(70, credit.get().balanceAfter());
        assertFalse(credit.get().bonus());
        assertEquals(70, economy.balance(alice));
        assertEquals(0, new BigDecimal("30").compareTo(economy.accrual().coins(alice)));
        assertEquals(0, new BigDecimal("3").compareTo(economy.accrual().xp(alice)));
        assertEquals(1, economy.kills(alice));
    }

    @Test
    void aHeadshotOrCriticalKillPays77() {
        assertEquals(77, economy.creditKill(1, new KillBlow(alice, false, true)).orElseThrow().reward().matchCurrency());
        assertEquals(77, economy.creditKill(2, new KillBlow(alice, true, false)).orElseThrow().reward().matchCurrency());
        assertEquals(77, economy.creditKill(3, new KillBlow(alice, true, true)).orElseThrow().reward().matchCurrency());
        assertEquals(231, economy.balance(alice));
        assertEquals(0, new BigDecimal("99.0").compareTo(economy.accrual().coins(alice)));
    }

    @Test
    void ghostsNonMembersAndEndedMatchesEarnNothing() {
        economy.setGhost(alice, true);
        assertTrue(economy.creditKill(1, new KillBlow(alice, false, false)).isEmpty());
        economy.setGhost(alice, false);
        assertTrue(economy.creditKill(2, new KillBlow(UUID.randomUUID(), false, false)).isEmpty());
        assertTrue(economy.creditKill(3, new KillBlow(alice, false, false)).isPresent());
        economy.end();
        assertTrue(economy.creditKill(4, new KillBlow(alice, false, false)).isEmpty());
        assertFalse(economy.canAfford(alice, 1));
        assertEquals(70, economy.balance(alice));
    }

    @Test
    void transfersAndChargesGoThroughTheLedger() {
        economy.creditKill(1, new KillBlow(alice, false, false));
        economy.creditKill(2, new KillBlow(alice, false, false));
        LedgerResult transfer = economy.transfer("pay-1", alice, bob, 50);
        assertTrue(transfer.accepted());
        assertEquals(90, economy.balance(alice));
        assertEquals(50, economy.balance(bob));
        assertTrue(economy.transfer("pay-1", alice, bob, 50) instanceof LedgerResult.Accepted a && a.replayed());
        assertEquals(50, economy.balance(bob));
        LedgerResult charge = economy.charge("buy-1", bob, 60, "purchase:1");
        assertEquals(LedgerResult.Reason.INSUFFICIENT_BALANCE, ((LedgerResult.Rejected) charge).reason());
        assertTrue(economy.charge("buy-2", bob, 50, "purchase:1").accepted());
        assertEquals(0, economy.balance(bob));
    }

    @Test
    void leavingDropsTheBalanceAndForfeitsTheAccrual() {
        economy.creditKill(1, new KillBlow(alice, false, false));
        economy.leave(alice);
        assertEquals(0, economy.balance(alice));
        assertFalse(economy.isMember(alice));
        assertEquals(0, BigDecimal.ZERO.compareTo(economy.accrual().coins(alice)));
    }
}
