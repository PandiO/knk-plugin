package net.knightsandkings.knk.paper.siege.survival.checkpoint;

import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MatchCheckpointDto;
import net.knightsandkings.knk.api.impl.SiegeSurvivalMatchApiImpl;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.paper.siege.survival.SurvivalMatchFixture;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** DESIGN §10 (link S8b): what the checkpoint carries, when it is written, latest-wins, failure logging. */
class SurvivalCheckpointsTest {

    private final SiegeSurvivalMatchApiImpl api = mock(SiegeSurvivalMatchApiImpl.class);
    private final List<MatchCheckpointDto> sent = new ArrayList<>();
    private final List<CompletableFuture<MatchCheckpointDto>> answers = new ArrayList<>();
    private final List<LogRecord> logs = new ArrayList<>();
    private final Logger logger = Logger.getLogger("SurvivalCheckpointsTest-" + System.nanoTime());

    SurvivalCheckpointsTest() {
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logs.add(record);
            }

            @Override
            public void flush() { }

            @Override
            public void close() { }
        });
        when(api.putCheckpoint(eq(42L), any())).thenAnswer(inv -> {
            sent.add(inv.getArgument(1));
            CompletableFuture<MatchCheckpointDto> f = new CompletableFuture<>();
            answers.add(f);
            return f;
        });
    }

    private static MatchCheckpointDto stored(MatchCheckpointDto body, int revision) {
        return new MatchCheckpointDto(body.siegeMatchId(), body.siegeScenarioId(), body.difficultyProfileId(), body.capturedAt(), body.substate(),
            body.wave(), body.completedWave(), body.specialProfileKey(), body.aliveEnemies(), body.queuedEnemies(), body.npcHeldObjectiveIds(),
            body.activeSpawnZoneIds(), body.doorRepairs(), body.players(), body.capturedAt(), revision);
    }

    private SurvivalCheckpoints install(SurvivalMatchFixture f, long intervalMillis) {
        SurvivalCheckpoints checkpoints = new SurvivalCheckpoints(f.match, api, () -> f.apiMatchId, f.lobby::userIdOf,
            id -> id.equals(f.players.get(0).getUniqueId()) ? OptionalInt.of(5) : OptionalInt.empty(),
            id -> id.equals(f.players.get(0).getUniqueId()) ? Optional.of("m42-p1-roll") : Optional.empty(), intervalMillis, Runnable::run, logger);
        f.match.addHook(checkpoints);
        return checkpoints;
    }

    @Test
    void roundStartWritesTheMatchStateAndRoundEndFollows() throws Exception {
        SurvivalMatchFixture f = new SurvivalMatchFixture(2, SurvivalMatchFixture.SPECIAL_AT_WAVE_1);
        f.apiMatchId = OptionalLong.of(42);
        SurvivalCheckpoints checkpoints = install(f, 0);
        f.startIntoWave1();
        assertEquals(1, sent.size(), "round 1 start");
        MatchCheckpointDto c = sent.get(0);
        assertEquals(42, c.siegeMatchId());
        assertEquals(1, c.siegeScenarioId());
        assertEquals(7, c.difficultyProfileId());
        assertEquals("WAVE_ACTIVE", c.substate());
        assertEquals(1, c.wave());
        assertEquals(0, c.completedWave());
        assertEquals("wolves", c.specialProfileKey());
        assertEquals(0, c.aliveEnemies(), "written at the round start, before the tick's first spawn");
        assertEquals(2, c.queuedEnemies());
        assertTrue(c.npcHeldObjectiveIds().isEmpty());
        assertEquals(List.of(10), c.activeSpawnZoneIds());
        assertTrue(c.doorRepairs().isEmpty(), "door repairs are S6");
        assertEquals(2, c.players().size());
        assertEquals(101, c.players().get(0).userId());
        assertFalse(c.players().get(0).ghost());
        assertEquals(5, c.players().get(0).kitId());
        assertEquals(0L, c.players().get(0).matchBalance());
        assertEquals("m42-p1-roll", c.players().get(0).pendingMysteryBoxRollId());
        assertNull(c.players().get(1).kitId());
        assertNull(c.players().get(1).pendingMysteryBoxRollId());
        assertEquals(1_002_100L, c.capturedAt().toInstant().toEpochMilli());

        answers.get(0).complete(stored(c, 1));
        assertEquals(1, checkpoints.revision());
        f.spawnAll();
        f.match.onMobDeath(f.spawned.get(0), f.players.get(0));
        f.match.onMobDeath(f.spawned.get(1), f.players.get(0));
        assertEquals(2, sent.size(), "round 1 end");
        MatchCheckpointDto end = sent.get(1);
        assertEquals("INTERMISSION", end.substate());
        assertEquals(1, end.completedWave());
        assertNull(end.specialProfileKey());
        assertEquals(0, end.aliveEnemies());
        assertEquals(2, end.players().get(0).kills());
        assertEquals(140L, end.players().get(0).matchBalance());
        answers.get(1).complete(stored(end, 2));
        assertEquals(2, checkpoints.revision());
        assertTrue(checkpoints.statusLines().get(0).contains("API match 42, revision 2"), checkpoints.statusLines().toString());
    }

    @Test
    void deathsRespawnsAndTheFinalAreWrittenAndCoalescedWhileOneIsInFlight() throws Exception {
        SurvivalMatchFixture f = new SurvivalMatchFixture(2);
        f.apiMatchId = OptionalLong.of(42);
        SurvivalCheckpoints checkpoints = install(f, 0);
        f.startIntoWave1();
        assertEquals(1, sent.size());
        // Two state changes while the round-start checkpoint is still in flight: one follow-up, with the latest state.
        f.match.onPlayerDeath(f.players.get(0));
        f.match.onPlayerDeath(f.players.get(1));
        assertEquals(1, sent.size(), "coalesced behind the in-flight send");
        assertTrue(f.ended.contains(net.knightsandkings.knk.core.survival.end.SurvivalEndReason.TEAM_WIPE), "both dead: team wipe");
        answers.get(0).complete(stored(sent.get(0), 1));
        assertEquals(2, sent.size(), "exactly one follow-up");
        assertTrue(sent.get(1).players().get(0).ghost() && sent.get(1).players().get(1).ghost());
        assertEquals("ENDING", sent.get(1).substate());
        answers.get(1).complete(stored(sent.get(1), 2));
        checkpoints.requestFinal();
        assertEquals(3, sent.size(), "the final checkpoint before the result");
        answers.get(2).complete(stored(sent.get(2), 3));
        checkpoints.ticked(f.clock.get() + 100_000);
        assertEquals(3, sent.size(), "nothing after the final");
        assertEquals(3, checkpoints.sent());
    }

    @Test
    void theIntervalWritesAndFailuresAreLoggedOnceAMinute() throws Exception {
        SurvivalMatchFixture f = new SurvivalMatchFixture(1);
        f.apiMatchId = OptionalLong.of(42);
        SurvivalCheckpoints checkpoints = install(f, 30_000);
        f.startIntoWave1();
        assertEquals(1, sent.size(), "the first tick's interval checkpoint (PREPARING); the round start is coalesced behind it");
        assertEquals("PREPARING", sent.get(0).substate());
        answers.get(0).completeExceptionally(new ApiException("url", "down", new IOException("refused")));
        assertEquals(1, checkpoints.failures());
        assertEquals(1, logs.size(), "the first failure is logged");
        assertEquals(2, sent.size(), "the coalesced round-start checkpoint follows");
        assertEquals("WAVE_ACTIVE", sent.get(1).substate());
        answers.get(1).completeExceptionally(new ApiException("url", "down", new IOException("refused")));
        assertEquals(1, logs.size(), "same minute: not logged again");
        f.advance(29_000);
        assertEquals(2, sent.size(), "interval not due yet (counted from the last request)");
        f.advance(1_000);
        assertEquals(3, sent.size(), "30 s after the last request");
        answers.get(2).completeExceptionally(new ApiException("url", 503, "down", ""));
        assertEquals(1, logs.size(), "within the minute: not logged again");
        f.advance(30_000);
        assertEquals(4, sent.size());
        answers.get(3).completeExceptionally(new ApiException("url", 503, "down", ""));
        assertEquals(2, logs.size(), "a minute after the first: logged again");
        assertEquals(4, checkpoints.failures());
    }

    @Test
    void withoutAMatchIdTheCheckpointIsSkippedAndCounted() throws Exception {
        SurvivalMatchFixture f = new SurvivalMatchFixture(1);
        SurvivalCheckpoints checkpoints = install(f, 0);
        f.startIntoWave1();
        assertTrue(sent.isEmpty());
        assertEquals(0, checkpoints.sent());
        assertTrue(checkpoints.statusLines().get(0).contains("skipped without match id 1"), checkpoints.statusLines().toString());
        f.apiMatchId = OptionalLong.of(42);
        f.match.onPlayerDeath(f.players.get(0));
        assertEquals(1, sent.size(), "the next trigger sends once the id is known");
    }
}
