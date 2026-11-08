package net.knightsandkings.knk.paper.siege.survival.results;

import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.MatchResultDto;
import net.knightsandkings.knk.api.dto.SiegeSurvivalDtos.SettlementDto;
import net.knightsandkings.knk.api.impl.SiegeSurvivalMatchApiImpl;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;
import net.knightsandkings.knk.paper.siege.survival.SurvivalMatchFixture;
import net.knightsandkings.knk.paper.siege.survival.recovery.SurvivalRecoveryStore;
import net.knightsandkings.knk.paper.siege.survival.recovery.SurvivalRecoveryStore.State;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The end of a survival match on the plugin side (link S8b): screen, persistence, post, leavers, recovery replay. */
class SurvivalResultServiceTest {

    @TempDir
    Path dir;

    private final SiegeSurvivalMatchApiImpl api = mock(SiegeSurvivalMatchApiImpl.class);
    private final Logger logger = Logger.getLogger("test");
    private final List<String> broadcasts = new ArrayList<>();
    private final Map<UUID, List<String>> told = new LinkedHashMap<>();
    private final SurvivalResultService.Players players = new SurvivalResultService.Players() {
        @Override
        public String nameOf(UUID playerId) {
            return "p-" + playerId.toString().substring(0, 4);
        }

        @Override
        public void tell(UUID playerId, Component message) {
            told.computeIfAbsent(playerId, k -> new ArrayList<>()).add(plain(message));
        }

        @Override
        public void broadcast(Component message) {
            broadcasts.add(plain(message));
        }
    };
    private final Instant now = Instant.parse("2026-11-08T12:20:00Z");

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private SurvivalResultService service() {
        SurvivalRecoveryStore store = new SurvivalRecoveryStore(new File(dir.toFile(), "matches"), logger);
        SurvivalResultPoster poster = new SurvivalResultPoster(api, millis -> Runnable::run, 2, 0, 0, logger);
        return new SurvivalResultService(new SurvivalResultServices(api, store, Runnable::run, 0, poster), players, Clock.fixed(now, ZoneOffset.UTC), logger);
    }

    private static MatchResultDto stored(MatchResultDto body, boolean settled) {
        SettlementDto s = body.settlement();
        return new MatchResultDto(body.siegeMatchId(), body.siegeLobbyId(), body.siegeScenarioId(), body.difficultyProfileId(),
            body.configurationGeneratedAt(), body.startedAt(), body.endedAt(), body.endReason(), body.reachedWave(), body.completedWave(),
            body.earnedKills(), body.systemKills(), body.players(),
            new SettlementDto(s.settlesRewards(), s.totalCoins(), s.totalXp(), s.ledgerKey(), settled ? OffsetDateTime.now() : null,
                settled ? "posting-1" : null, settled ? body.players().size() : 0), null, "v1", "Plugin", OffsetDateTime.now());
    }

    @Test
    void aTeamWipeIsShownPersistedPostedAndSettled() throws Exception {
        SurvivalMatchFixture f = new SurvivalMatchFixture(2);
        f.apiMatchId = OptionalLong.of(42);
        SurvivalResultService service = service();
        service.matchStarted(f.lobby, f.match, id -> OptionalInt.of(5), id -> Optional.empty());
        ArgumentCaptor<MatchResultDto> posted = ArgumentCaptor.forClass(MatchResultDto.class);
        when(api.postResult(eq(42L), posted.capture())).thenAnswer(inv -> CompletableFuture.completedFuture(stored(inv.getArgument(1), true)));
        when(api.putCheckpoint(anyLong(), any())).thenReturn(CompletableFuture.completedFuture(null));

        f.startIntoWave1();
        f.spawnAll();
        UUID alice = f.players.get(0).getUniqueId();
        UUID bob = f.players.get(1).getUniqueId();
        f.match.onMobDeath(f.spawned.get(0), f.players.get(0));
        f.match.onMobDeath(f.spawned.get(1), f.players.get(0));
        f.advance(2_100); // round 2
        f.spawnAll();
        f.match.onMobDeath(f.spawned.get(2), f.players.get(1));
        // bob leaves during round 2 (S62): remembered, forfeits
        service.memberLeft(f.lobby, bob, 102, true);
        f.siege.roster().remove(bob);
        assertFalse(f.match.economy().isMember(bob));
        f.siege.roster().recordDeath(alice, null, KnkSiegeConfiguration.legacyDefaults()); // what SiegeService.handleMemberDeath records
        f.match.onPlayerDeath(f.players.get(0));
        assertEquals(List.of(SurvivalEndReason.TEAM_WIPE), f.ended);

        assertTrue(service.present(f.lobby, f.match));
        MatchResultDto body = posted.getValue();
        assertEquals("TeamWipe", body.endReason());
        assertEquals(2, body.reachedWave());
        assertEquals(1, body.completedWave());
        assertEquals(3, body.earnedKills());
        assertEquals(2, body.players().size());
        var a = body.players().get(0);
        assertEquals(101, a.userId());
        assertTrue(a.presentAtEnd());
        assertEquals(2, a.kills());
        assertEquals(1, a.deaths());
        assertEquals(5, a.kitId());
        assertEquals(140L, a.finalMatchBalance());
        assertEquals(60, a.coinsSettled());
        assertEquals(6, a.xpSettled());
        var b = body.players().get(1);
        assertEquals(102, b.userId());
        assertFalse(b.presentAtEnd());
        assertEquals(now.atOffset(ZoneOffset.UTC), b.leftAt());
        assertEquals(1, b.kills(), "kills stay history");
        assertEquals(0, b.coinsSettled(), "leavers forfeit");
        assertEquals(0L, b.finalMatchBalance());
        assertTrue(body.settlement().settlesRewards());
        assertEquals(60, body.settlement().totalCoins());
        assertEquals("siege-survival:42", body.settlement().ledgerKey());
        assertEquals(OffsetDateTime.parse("2026-11-08T12:00:00Z"), body.startedAt());
        assertEquals(now.atOffset(ZoneOffset.UTC), body.endedAt());
        assertEquals(OffsetDateTime.parse("2026-11-08T10:00:00Z"), body.configurationGeneratedAt());

        assertEquals(1, broadcasts.size());
        assertTrue(broadcasts.get(0).contains("Test lobby") && broadcasts.get(0).contains("wiped out"), broadcasts.toString());
        List<String> aliceLines = told.get(alice);
        assertTrue(aliceLines.stream().anyMatch(l -> l.contains("Permanent reward: +60 coins, +6 XP")), aliceLines.toString());
        assertTrue(aliceLines.stream().anyMatch(l -> l.contains("Match coins: 140 left")), aliceLines.toString());
        assertTrue(aliceLines.get(aliceLines.size() - 1).contains("Rewards credited: +60 coins, +6 XP"), aliceLines.toString());
        assertNull(told.get(bob), "the leaver is gone");
        assertFalse(service.services().store().has(42), "posted: the recovery file is deleted");
        assertTrue(service.statusLines().get(0).contains("posted") && service.statusLines().get(0).contains("posting-1"), service.statusLines().toString());
        assertTrue(service.present(f.lobby, f.match), "a second present is a no-op");
        assertEquals(1, broadcasts.size());
        verify(api, org.mockito.Mockito.atLeastOnce()).putCheckpoint(eq(42L), any()); // round start/end, leave, death and the final one before the result
    }

    @Test
    void anAdminAbortPostsWithoutSettlementAndAMismatchKeepsNoFile() throws Exception {
        SurvivalMatchFixture f = new SurvivalMatchFixture(1);
        f.apiMatchId = OptionalLong.of(42);
        SurvivalResultService service = service();
        when(api.conflictCode(any(ApiException.class))).thenReturn("SettlementMismatch");
        when(api.postResult(eq(42L), any())).thenReturn(CompletableFuture.failedFuture(
            new ApiException("url", 409, "conflict", "{\"code\":\"SettlementMismatch\",\"message\":\"totals differ\"}")));
        f.startIntoWave1();
        f.match.endedExternally(SurvivalEndReason.ADMIN_ABORT);
        assertTrue(service.present(f.lobby, f.match));
        ArgumentCaptor<MatchResultDto> posted = ArgumentCaptor.forClass(MatchResultDto.class);
        verify(api).postResult(eq(42L), posted.capture());
        assertEquals("AdminAbort", posted.getValue().endReason());
        assertFalse(posted.getValue().settlement().settlesRewards());
        assertNull(posted.getValue().settlement().ledgerKey());
        assertFalse(service.services().store().has(42), "a final rejection does not keep the file");
        assertTrue(service.statusLines().get(0).contains("failed (SettlementMismatch)"), service.statusLines().toString());
        List<String> lines = told.get(f.players.get(0).getUniqueId());
        assertTrue(lines.stream().anyMatch(l -> l.contains("Permanent reward: none") && l.contains("stopped by an admin")), lines.toString());
    }

    @Test
    void aTransportFailureKeepsTheResultFileForTheNextStart() throws Exception {
        SurvivalMatchFixture f = new SurvivalMatchFixture(1);
        f.apiMatchId = OptionalLong.of(42);
        SurvivalResultService service = service();
        when(api.postResult(eq(42L), any())).thenReturn(CompletableFuture.failedFuture(new ApiException("url", "down", new java.io.IOException("x"))));
        f.startIntoWave1();
        f.match.end(SurvivalEndReason.TEAM_WIPE);
        assertTrue(service.present(f.lobby, f.match));
        assertTrue(service.services().store().has(42));
        assertEquals(State.RESULT, service.services().store().list().get(0).state());
        assertEquals("TeamWipe", service.services().store().list().get(0).result().endReason());
    }

    @Test
    void aResultWithoutAMatchIdWaitsForItAndIsDroppedAfterAMinute() throws Exception {
        SurvivalMatchFixture f = new SurvivalMatchFixture(1);
        SurvivalResultService service = service();
        service.matchStarted(f.lobby, f.match, id -> OptionalInt.empty(), id -> Optional.empty());
        when(api.postResult(eq(42L), any())).thenAnswer(inv -> CompletableFuture.completedFuture(stored(inv.getArgument(1), true)));
        f.startIntoWave1();
        f.match.end(SurvivalEndReason.TEAM_WIPE);
        assertTrue(service.present(f.lobby, f.match));
        verify(api, never()).postResult(anyLong(), any());
        assertTrue(service.statusLines().get(0).contains("waiting for the API match id"));
        service.tick(now.toEpochMilli() + 1_000);
        verify(api, never()).postResult(anyLong(), any());
        f.apiMatchId = OptionalLong.of(42);
        service.tick(now.toEpochMilli() + 2_000);
        verify(api).postResult(eq(42L), any());

        // A second match whose id never comes is dropped after the bound.
        SurvivalMatchFixture g = new SurvivalMatchFixture(1);
        when(g.lobby.id()).thenReturn(2);
        when(g.lobby.key()).thenReturn("other");
        service.matchStarted(g.lobby, g.match, id -> OptionalInt.empty(), id -> Optional.empty());
        g.startIntoWave1();
        g.match.end(SurvivalEndReason.TEAM_WIPE);
        assertTrue(service.present(g.lobby, g.match));
        service.tick(now.toEpochMilli() + SurvivalResultService.MATCH_ID_WAIT_MILLIS + 1);
        assertTrue(service.statusLines().stream().anyMatch(l -> l.contains("other") && l.contains("NoMatchId")), service.statusLines().toString());
    }

    @Test
    void theOpenMarkerIsWrittenOnceTheIdIsKnownAndReplayedAsAServerRestartResult() throws Exception {
        SurvivalMatchFixture f = new SurvivalMatchFixture(1);
        SurvivalResultService service = service();
        service.matchStarted(f.lobby, f.match, id -> OptionalInt.empty(), id -> Optional.empty());
        when(api.putCheckpoint(anyLong(), any())).thenReturn(CompletableFuture.completedFuture(null));
        f.startIntoWave1();
        assertTrue(service.services().store().list().isEmpty(), "no id, no marker");
        f.apiMatchId = OptionalLong.of(42);
        service.tick(now.toEpochMilli());
        List<SurvivalRecoveryStore.Entry> entries = service.services().store().list();
        assertEquals(1, entries.size());
        assertEquals(State.OPEN, entries.get(0).state());
        assertEquals("ServerRestart", entries.get(0).result().endReason());
        assertEquals(1, entries.get(0).result().reachedWave());
        assertEquals(101, entries.get(0).result().players().get(0).userId());

        // Next start: the open marker becomes a ServerRestart result; the API already has the abort row.
        SurvivalResultService next = service();
        when(api.conflictCode(any(ApiException.class))).thenReturn("ResultAlreadyStored");
        ArgumentCaptor<MatchResultDto> posted = ArgumentCaptor.forClass(MatchResultDto.class);
        when(api.postResult(eq(42L), posted.capture())).thenReturn(CompletableFuture.failedFuture(
            new ApiException("url", 409, "conflict", "{\"code\":\"ResultAlreadyStored\",\"message\":\"abort row\"}")));
        MatchResultDto abortRow = new MatchResultDto(42, 1, 1, 7, null, null, null, "ServerRestart", 1, 0, 0, 0, List.of(),
            new SettlementDto(false, 0, 0, null, null, null, null), null, "v1", "Abort", OffsetDateTime.now());
        when(api.getResult(42L)).thenReturn(CompletableFuture.completedFuture(abortRow));
        assertEquals(1, next.recoverOnStartup());
        MatchResultDto body = posted.getValue();
        assertEquals("ServerRestart", body.endReason());
        assertEquals(now.atOffset(ZoneOffset.UTC), body.endedAt());
        assertFalse(body.settlement().settlesRewards());
        assertEquals(0, body.settlement().totalCoins());
        assertNull(body.settlement().ledgerKey());
        assertEquals(0, body.players().get(0).coinsSettled());
        assertFalse(next.services().store().has(42), "the stored row answers; the file is gone");
        assertTrue(next.statusLines().get(0).contains("recovery") && next.statusLines().get(0).contains("posted"), next.statusLines().toString());
    }

    @Test
    void aPendingResultFileIsPostedAgainAtStartup() throws Exception {
        SurvivalResultService service = service();
        MatchResultDto pending = new MatchResultDto(43, 1, 1, 7, null, OffsetDateTime.now(), OffsetDateTime.now(), "TeamWipe", 3, 2, 5, 0, List.of(),
            new SettlementDto(true, 30, 3, "siege-survival:43", null, null, null), null, null, null, null);
        service.services().store().save(43, State.RESULT, pending);
        ArgumentCaptor<MatchResultDto> posted = ArgumentCaptor.forClass(MatchResultDto.class);
        when(api.postResult(eq(43L), posted.capture())).thenAnswer(inv -> CompletableFuture.completedFuture(stored(inv.getArgument(1), true)));
        assertEquals(1, service.recoverOnStartup());
        assertEquals("TeamWipe", posted.getValue().endReason(), "posted as it was, not as a restart");
        assertEquals("siege-survival:43", posted.getValue().settlement().ledgerKey());
        assertFalse(service.services().store().has(43));
    }
}
