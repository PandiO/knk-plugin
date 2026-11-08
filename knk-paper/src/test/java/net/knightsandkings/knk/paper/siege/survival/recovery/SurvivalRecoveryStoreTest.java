package net.knightsandkings.knk.paper.siege.survival.recovery;

import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MatchResultDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.PlayerResultDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.SettlementDto;
import net.knightsandkings.knk.paper.siege.survival.recovery.SurvivalRecoveryStore.Entry;
import net.knightsandkings.knk.paper.siege.survival.recovery.SurvivalRecoveryStore.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recovery file round-trips the result body (S61, DESIGN §9 "persist before the payout"). */
class SurvivalRecoveryStoreTest {

    @TempDir
    Path dir;

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-11-08T12:00:00Z");

    private static MatchResultDto body(String reason) {
        return new MatchResultDto(42, 9, 1, 7, T0.minusHours(2), T0, T0.plusMinutes(20), reason, 10, 9, 4, 1,
            List.of(new PlayerResultDto(101, true, null, 5, 3, 1, 1, 0.0, 224L, 100L, 124L, new BigDecimal("93.0"), new BigDecimal("9.9"), 93, 9, null, null, null),
                new PlayerResultDto(102, false, T0.plusMinutes(10), null, 1, 0, 2, 0.0, 70L, 0L, 0L, new BigDecimal("30"), new BigDecimal("3"), 0, 0, null, null, null)),
            new SettlementDto(true, 93, 9, "siege-survival:42", null, null, null), null, null, null, null);
    }

    @Test
    void saveListDeleteRoundTrip() throws Exception {
        SurvivalRecoveryStore store = new SurvivalRecoveryStore(new File(dir.toFile(), "matches"), Logger.getLogger("test"));
        assertTrue(store.list().isEmpty(), "no directory yet: nothing listed");
        assertTrue(store.save(42, State.OPEN, body("ServerRestart")));
        assertTrue(store.save(43, State.RESULT, body("TeamWipe")));
        assertTrue(store.has(42));
        assertTrue(Files.isRegularFile(dir.resolve("matches").resolve("42.yml")));
        assertFalse(Files.exists(dir.resolve("matches").resolve("42.yml.tmp")));

        List<Entry> entries = store.list();
        assertEquals(2, entries.size());
        Entry open = entries.get(0);
        assertEquals(42, open.matchId());
        assertEquals(State.OPEN, open.state());
        MatchResultDto r = open.result();
        assertEquals(42, r.siegeMatchId());
        assertEquals(9, r.siegeLobbyId());
        assertEquals(1, r.siegeScenarioId());
        assertEquals(7, r.difficultyProfileId());
        assertEquals(T0.minusHours(2), r.configurationGeneratedAt());
        assertEquals(T0, r.startedAt());
        assertEquals(T0.plusMinutes(20), r.endedAt());
        assertEquals("ServerRestart", r.endReason());
        assertEquals(10, r.reachedWave());
        assertEquals(9, r.completedWave());
        assertEquals(4, r.earnedKills());
        assertEquals(1, r.systemKills());
        assertEquals(2, r.players().size());
        PlayerResultDto a = r.players().get(0);
        assertEquals(101, a.userId());
        assertTrue(a.presentAtEnd());
        assertNull(a.leftAt());
        assertEquals(5, a.kitId());
        assertEquals(3, a.kills());
        assertEquals(1, a.bonusKills());
        assertEquals(1, a.deaths());
        assertEquals(224L, a.matchCurrencyEarned());
        assertEquals(100L, a.matchCurrencySpent());
        assertEquals(124L, a.finalMatchBalance());
        assertEquals(0, new BigDecimal("93.0").compareTo(a.coinsAccrued()));
        assertEquals(0, new BigDecimal("9.9").compareTo(a.xpAccrued()));
        assertEquals(93, a.coinsSettled());
        assertEquals(9, a.xpSettled());
        PlayerResultDto b = r.players().get(1);
        assertFalse(b.presentAtEnd());
        assertEquals(T0.plusMinutes(10), b.leftAt());
        assertNull(b.kitId());
        assertTrue(r.settlement().settlesRewards());
        assertEquals(93, r.settlement().totalCoins());
        assertEquals("siege-survival:42", r.settlement().ledgerKey());

        assertEquals(State.RESULT, entries.get(1).state());
        assertTrue(store.save(42, State.RESULT, body("TeamWipe")), "overwrites in place");
        assertEquals(State.RESULT, store.list().get(0).state());
        assertTrue(store.delete(42));
        assertFalse(store.has(42));
        assertTrue(store.delete(42), "deleting twice is fine");
        assertEquals(1, store.list().size());
    }

    @Test
    void unreadableFilesAreSkipped() throws Exception {
        File matches = new File(dir.toFile(), "matches");
        SurvivalRecoveryStore store = new SurvivalRecoveryStore(matches, Logger.getLogger("test"));
        assertTrue(store.save(1, State.OPEN, body("ServerRestart")));
        Files.writeString(matches.toPath().resolve("garbage.yml"), "state: [unclosed");
        Files.writeString(matches.toPath().resolve("2.yml"), "match-id: 0\nstate: OPEN\n");
        assertEquals(1, store.list().size());
    }
}
