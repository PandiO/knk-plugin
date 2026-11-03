package net.knightsandkings.knk.core.survival.economy;

import net.knightsandkings.knk.core.survival.wave.KillCause;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S32, S33, S58, §9: 30 coins / 3 XP / 70 match currency per kill, ×1.1 once for headshot or crit, nothing else. */
class KillRewardCalculatorTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final KillRewardCalculator calculator = new KillRewardCalculator(KillRewardSettings.defaults());

    @Test
    void normalKillPaysTheDefaults() {
        KillReward reward = calculator.reward(EnemyKill.byPlayer(1, ALICE, false, false));
        assertEquals(new KillReward(new BigDecimal("30"), new BigDecimal("3"), 70), reward);
    }

    @Test
    void headshotOrCriticalPaysOneTenthMoreOnce() {
        KillReward headshot = calculator.reward(EnemyKill.byPlayer(1, ALICE, true, false));
        assertEquals(0, new BigDecimal("33.0").compareTo(headshot.coins()));
        assertEquals(0, new BigDecimal("3.3").compareTo(headshot.xp()));
        assertEquals(77, headshot.matchCurrency());
        assertEquals(headshot, calculator.reward(EnemyKill.byPlayer(1, ALICE, false, true)));
        assertEquals(headshot, calculator.reward(EnemyKill.byPlayer(1, ALICE, true, true)), "both counts ×1.1 once");
    }

    @Test
    void systemEnvironmentNpcAndGhostKillsPayNothing() {
        assertEquals(KillReward.NONE, calculator.reward(EnemyKill.bySystem(1)));
        assertEquals(KillReward.NONE, calculator.reward(EnemyKill.byEnvironment(1)));
        assertEquals(KillReward.NONE, calculator.reward(EnemyKill.byNpc(1)));
        EnemyKill ghostKill = new EnemyKill(1, KillCause.PLAYER, Optional.of(ALICE), true, true, true);
        assertEquals(KillReward.NONE, calculator.reward(ghostKill));
    }

    @Test
    void matchCurrencyIsRoundedDownPerKillCoinsAndXpAreExact() {
        KillRewardCalculator odd = new KillRewardCalculator(new KillRewardSettings(new BigDecimal("25"), new BigDecimal("2.5"), 75,
            new BigDecimal("1.1")));
        KillReward reward = odd.reward(EnemyKill.byPlayer(1, ALICE, true, false));
        assertEquals(82, reward.matchCurrency(), "82.5 floored per credit");
        assertEquals(0, new BigDecimal("27.5").compareTo(reward.coins()));
        assertEquals(0, new BigDecimal("2.75").compareTo(reward.xp()));
    }

    @Test
    void settingsDeriveFromCaptureRewardsDividedByTen() {
        KillRewardSettings s = KillRewardSettings.fromCaptureRewards(300, 30);
        assertEquals(0, new BigDecimal("30").compareTo(s.coinsPerKill()));
        assertEquals(0, new BigDecimal("3").compareTo(s.xpPerKill()));
        assertEquals(70, s.matchCurrencyPerKill());
        KillRewardSettings odd = KillRewardSettings.fromCaptureRewards(305, 33);
        assertEquals(0, new BigDecimal("30.5").compareTo(odd.coinsPerKill()));
    }

    @Test
    void accrualRoundsDownOnceAtSettlementAndLeaversForfeit() {
        PermanentRewardAccrual accrual = new PermanentRewardAccrual();
        KillReward headshot = calculator.reward(EnemyKill.byPlayer(1, ALICE, true, false));
        for (int i = 0; i < 3; i++) accrual.add(ALICE, headshot); // 9.9 XP, 99 coins
        accrual.add(BOB, calculator.reward(EnemyKill.byPlayer(2, BOB, false, false)));
        accrual.add(BOB, KillReward.NONE);
        assertEquals(0, new BigDecimal("9.9").compareTo(accrual.xp(ALICE)));
        assertEquals(4, accrual.earnedKills());

        Map<UUID, PermanentRewardAccrual.Settlement> both = accrual.settle(List.of(ALICE, BOB));
        assertEquals(new PermanentRewardAccrual.Settlement(99, 9), both.get(ALICE), "9.9 → 9 once, not 3 × 3");
        assertEquals(new PermanentRewardAccrual.Settlement(30, 3), both.get(BOB));

        Map<UUID, PermanentRewardAccrual.Settlement> aliceLeft = accrual.settle(List.of(BOB));
        assertTrue(!aliceLeft.containsKey(ALICE));
        accrual.forfeit(BOB);
        assertTrue(accrual.settle(List.of(ALICE, BOB)).containsKey(ALICE));
        assertTrue(!accrual.settle(List.of(ALICE, BOB)).containsKey(BOB));
    }
}
