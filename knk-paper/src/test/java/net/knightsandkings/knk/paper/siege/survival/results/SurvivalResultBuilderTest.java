package net.knightsandkings.knk.paper.siege.survival.results;

import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MatchResultDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.PlayerResultDto;
import net.knightsandkings.knk.api.impl.SiegeSurvivalMatchApiImpl;
import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;
import net.knightsandkings.knk.core.survival.result.SurvivalResultRules;
import net.knightsandkings.knk.core.survival.wave.WaveRecord;
import net.knightsandkings.knk.paper.siege.survival.results.SurvivalResultInput.PlayerResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The S8a result contract from the plugin side: every mapping, leavers, floor once, settles flag per reason. */
class SurvivalResultBuilderTest {

    private static final OffsetDateTime STARTED = OffsetDateTime.parse("2026-11-08T12:00:00Z");
    private static final OffsetDateTime ENDED = OffsetDateTime.parse("2026-11-08T12:20:00Z");
    private static final OffsetDateTime GENERATED = OffsetDateTime.parse("2026-11-08T10:00:00Z");
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private SurvivalResultInput input(SurvivalEndReason reason) {
        PlayerResult present = new PlayerResult(alice, "alice", 101, true, null, OptionalInt.of(5), 3, 2, 1, 0.0, 224, 100, 124,
            new BigDecimal("93.0"), new BigDecimal("9.9"));
        PlayerResult leaver = new PlayerResult(bob, "bob", 102, false, OffsetDateTime.parse("2026-11-08T12:10:00Z"), OptionalInt.empty(),
            1, 0, 2, 0.0, 70, 0, 0, new BigDecimal("30"), new BigDecimal("3"));
        return new SurvivalResultInput(9, "Lobby", 1, OptionalInt.of(7), GENERATED, STARTED, ENDED, reason, new WaveRecord(10, 9), 4, 1,
            List.of(present, leaver));
    }

    @Test
    void everyFieldIsMappedForATeamWipe() {
        MatchResultDto r = SurvivalResultBuilder.build(42, input(SurvivalEndReason.TEAM_WIPE));
        assertEquals(42, r.siegeMatchId());
        assertEquals(9, r.siegeLobbyId());
        assertEquals(1, r.siegeScenarioId());
        assertEquals(7, r.difficultyProfileId());
        assertEquals(GENERATED, r.configurationGeneratedAt());
        assertEquals(STARTED, r.startedAt());
        assertEquals(ENDED, r.endedAt());
        assertEquals("TeamWipe", r.endReason());
        assertEquals(10, r.reachedWave());
        assertEquals(9, r.completedWave());
        assertEquals(4, r.earnedKills());
        assertEquals(1, r.systemKills());
        assertNull(r.equipmentMode(), "null = the scenario setting (flag S8b)");
        assertNull(r.rewardPolicyVersion());
        assertNull(r.source());
        assertNull(r.recordedAt());

        PlayerResultDto a = r.players().get(0);
        assertEquals(101, a.userId());
        assertTrue(a.presentAtEnd());
        assertNull(a.leftAt());
        assertEquals(5, a.kitId());
        assertEquals(3, a.kills());
        assertEquals(2, a.bonusKills());
        assertEquals(1, a.deaths());
        assertEquals(0.0, a.repairWork());
        assertEquals(224L, a.matchCurrencyEarned());
        assertEquals(100L, a.matchCurrencySpent());
        assertEquals(124L, a.finalMatchBalance());
        assertEquals(0, new BigDecimal("93.0").compareTo(a.coinsAccrued()));
        assertEquals(0, new BigDecimal("9.9").compareTo(a.xpAccrued()));
        assertEquals(93, a.coinsSettled());
        assertEquals(9, a.xpSettled(), "9.9 → 9: floored once at settlement");
        assertNull(a.mysteryBoxRolls());

        PlayerResultDto b = r.players().get(1);
        assertEquals(102, b.userId());
        assertFalse(b.presentAtEnd());
        assertEquals(OffsetDateTime.parse("2026-11-08T12:10:00Z"), b.leftAt());
        assertNull(b.kitId());
        assertEquals(1, b.kills());
        assertEquals(0, b.coinsSettled(), "leavers forfeit (S62)");
        assertEquals(0, b.xpSettled());
        assertEquals(0L, b.finalMatchBalance());

        assertTrue(r.settlement().settlesRewards());
        assertEquals(93, r.settlement().totalCoins());
        assertEquals(9, r.settlement().totalXp());
        assertEquals("siege-survival:42", r.settlement().ledgerKey());
        assertNull(r.settlement().settledAt());
        assertNull(r.settlement().ledgerPostingId());
    }

    @Test
    void abortsAndNoPlayersLeftSettleNothingAndCarryNoLedgerKey() {
        for (SurvivalEndReason reason : List.of(SurvivalEndReason.ADMIN_ABORT, SurvivalEndReason.SERVER_RESTART,
            SurvivalEndReason.TECHNICAL_LIMIT, SurvivalEndReason.NO_PLAYERS_LEFT)) {
            MatchResultDto r = SurvivalResultBuilder.build(42, input(reason));
            assertFalse(r.settlement().settlesRewards(), reason.name());
            assertEquals(0, r.settlement().totalCoins());
            assertEquals(0, r.settlement().totalXp());
            assertNull(r.settlement().ledgerKey());
            assertEquals(0, r.players().get(0).coinsSettled());
            assertEquals(0, new BigDecimal("93.0").compareTo(r.players().get(0).coinsAccrued()), "the exact accrual is still reported");
            assertEquals(SurvivalResultRules.endReasonCode(reason), r.endReason());
        }
        for (SurvivalEndReason reason : List.of(SurvivalEndReason.INSTA_LOSE_OBJECTIVE_CAPTURED, SurvivalEndReason.ALL_OBJECTIVES_CAPTURED)) {
            assertTrue(SurvivalResultBuilder.build(42, input(reason)).settlement().settlesRewards(), reason.name());
        }
    }

    @Test
    void theWireNamesAndRulesAgreeWithTheApiClient() {
        assertEquals(SiegeSurvivalMatchApiImpl.END_TEAM_WIPE, SurvivalResultRules.endReasonCode(SurvivalEndReason.TEAM_WIPE));
        assertEquals(SiegeSurvivalMatchApiImpl.END_INSTA_LOSE_OBJECTIVE_CAPTURED, SurvivalResultRules.endReasonCode(SurvivalEndReason.INSTA_LOSE_OBJECTIVE_CAPTURED));
        assertEquals(SiegeSurvivalMatchApiImpl.END_ALL_OBJECTIVES_CAPTURED, SurvivalResultRules.endReasonCode(SurvivalEndReason.ALL_OBJECTIVES_CAPTURED));
        assertEquals(SiegeSurvivalMatchApiImpl.END_NO_PLAYERS_LEFT, SurvivalResultRules.endReasonCode(SurvivalEndReason.NO_PLAYERS_LEFT));
        assertEquals(SiegeSurvivalMatchApiImpl.END_ADMIN_ABORT, SurvivalResultRules.endReasonCode(SurvivalEndReason.ADMIN_ABORT));
        assertEquals(SiegeSurvivalMatchApiImpl.END_SERVER_RESTART, SurvivalResultRules.endReasonCode(SurvivalEndReason.SERVER_RESTART));
        assertEquals(SiegeSurvivalMatchApiImpl.END_TECHNICAL_LIMIT, SurvivalResultRules.endReasonCode(SurvivalEndReason.TECHNICAL_LIMIT));
        for (SurvivalEndReason reason : SurvivalEndReason.values()) {
            assertEquals(SiegeSurvivalMatchApiImpl.settlesRewards(SurvivalResultRules.endReasonCode(reason)), SurvivalResultRules.settlesRewards(reason), reason.name());
        }
        assertEquals(SiegeSurvivalMatchApiImpl.ledgerKey(42), SurvivalResultRules.ledgerKey(42, SurvivalEndReason.TEAM_WIPE));
    }

    @Test
    void bonusKillsNeverExceedKillsAndTheInputGuardsItsOwnInvariants() {
        PlayerResult p = new PlayerResult(alice, null, 1, true, OffsetDateTime.now(), null, 2, 5, 0, 0, 0, 0, 0, null, null);
        assertEquals(2, p.bonusKills());
        assertNull(p.leftAt(), "present players have no leftAt");
        assertEquals("", p.name());
        assertEquals(0, BigDecimal.ZERO.compareTo(p.coinsAccrued()));
        SurvivalResultInput in = new SurvivalResultInput(1, null, 1, null, null, STARTED, ENDED, SurvivalEndReason.TEAM_WIPE, null, 0, 0, List.of(p));
        assertEquals(WaveRecord.NONE, in.waves());
        MatchResultDto r = SurvivalResultBuilder.build(1, in);
        assertEquals(2, r.players().get(0).bonusKills());
        assertNull(r.difficultyProfileId());
        SurvivalResultInput restart = in.withEnd(SurvivalEndReason.SERVER_RESTART, ENDED.plusMinutes(1));
        assertEquals(SurvivalEndReason.SERVER_RESTART, restart.reason());
        assertEquals(ENDED.plusMinutes(1), restart.endedAt());
        assertEquals(in.players(), restart.players());
    }
}
