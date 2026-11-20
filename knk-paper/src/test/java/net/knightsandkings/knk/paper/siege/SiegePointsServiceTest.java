package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ParticipantResult;
import net.knightsandkings.knk.core.siege.economy.SiegePointsSettings;
import net.knightsandkings.knk.paper.siege.points.SiegePointsService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static net.knightsandkings.knk.paper.siege.SiegePointsTestMatch.A1;
import static net.knightsandkings.knk.paper.siege.SiegePointsTestMatch.A2;
import static net.knightsandkings.knk.paper.siege.SiegePointsTestMatch.D1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Siege points in a PvP match (siege shop § 5.1, § 5.2, P07, P12–P18, P27; PandiO/knk-workspace#866): the service as a
 * match observer with the default amounts. A05–A09 end to end through the match events, A10 the action bar,
 * {@code /siege points} and the scoreboard line, A03 {@code /siege pay}, A04 and A11 the match end and its totals.
 */
class SiegePointsServiceTest {

    private MockedStatic<Bukkit> bukkit;
    private SiegePointsTestMatch fixture;
    private SiegePointsService service;
    private final Map<UUID, Instant> afk = new java.util.HashMap<>();

    private void start(boolean transfers) {
        KnkSiegeConfiguration config = KnkSiegeConfiguration.legacyDefaults()
                .withPoints(SiegePointsSettings.defaults().withTransfersEnabled(transfers));
        fixture = new SiegePointsTestMatch(config);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(any(UUID.class))).thenAnswer(i -> fixture.get(i.getArgument(0)));
        bukkit.when(() -> Bukkit.getPlayerExact(anyString())).thenAnswer(i -> fixture.players.values().stream()
                .filter(p -> p.getName().equals(i.getArgument(0))).findFirst().orElse(null));
        service = new SiegePointsService(Logger.getLogger("test"),
                id -> fixture.match.roster().contains(id) ? Optional.of(fixture.match) : Optional.empty(), afk::get, null);
        service.matchStarted(fixture.lobby, fixture.match);
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) bukkit.close();
    }

    private long balance(UUID id) {
        return service.economyOf(fixture.match).orElseThrow().balance(id);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private String lastActionBar(UUID id) {
        ArgumentCaptor<Component> bar = ArgumentCaptor.forClass(Component.class);
        verify(fixture.get(id), atLeastOnce()).sendActionBar(bar.capture());
        return plain(bar.getValue());
    }

    private void kill(UUID victim, UUID killer) {
        fixture.match.roster().recordDeath(victim, killer, fixture.match.configuration());
        int streak = killer == null ? 0 : fixture.match.roster().member(killer).orElseThrow().killStreak();
        service.memberDied(fixture.lobby, fixture.match, victim, killer, streak);
    }

    @Test
    void everyMemberStartsAtZeroWithTheirOwnScoreboardLine_A04_A10() {
        start(false);
        assertEquals(0, balance(A1));
        assertEquals("Siege points: 0", plain(service.scoreboardLine(fixture.match, A1)));
    }

    @Test
    void killsPayWithTheStreakAndTheActionBarShowsEachChange_A05_A07_A10() {
        start(false);
        kill(D1, A1);
        assertEquals(100, balance(A1));
        assertEquals("+100 kill · 100", lastActionBar(A1));
        assertEquals("+15 death · 15", lastActionBar(D1));
        kill(D1, A1);
        assertEquals("+110 kill ×1.1 · 210", lastActionBar(A1));
        kill(A1, D1);                       // A1's streak ends
        kill(D1, A1);
        assertEquals("+100 kill · 325", lastActionBar(A1), "210 + 15 death + 100");
    }

    @Test
    void enemyDamageInTheWindowEarnsAnAssist_A06() {
        start(false);
        service.recordDamage(fixture.get(A2), fixture.get(D1), 4.0);
        service.recordDamage(fixture.get(A1), fixture.get(A2), 9.0); // a teammate's hit is no assist
        kill(D1, A1);
        assertEquals(40, balance(A2));
        assertEquals("+40 assist · 40", lastActionBar(A2));
    }

    @Test
    void gateDamagePaysOnePointPerFiveHp_A08() {
        start(false);
        service.gateDamaged(fixture.lobby, fixture.match, A1, 50);
        assertEquals(10, balance(A1));
        assertEquals("+10 gate · 10", lastActionBar(A1));
    }

    @Test
    void theTricklePaysActiveMembersOnly_andNeverAnAfkOne_A09() {
        start(false);
        afk.put(D1, Instant.EPOCH);
        for (int s = 1; s <= 60; s++) {
            fixture.moveTo(A1, s);          // walks a block a second
            fixture.moveTo(D1, s);          // also walks, but is AFK
            service.secondTicked(fixture.lobby, fixture.match, fixture.match.board().step(Map.of()), Map.of());
        }
        assertEquals(10, balance(A1));
        assertEquals(0, balance(A2), "A2 stood still");
        assertEquals(0, balance(D1));
    }

    @Test
    void siegePointsCommandPrintsBalanceEarnedAndSpent_A10() {
        start(false);
        kill(D1, A1);
        assertTrue(plain(service.points(fixture.get(A1))).endsWith("Siege points: 100 (earned 100, spent 0 this match)"));
    }

    @Test
    void payIsRefusedWhileTransfersAreOff_A03() {
        start(false);
        kill(D1, A1);
        assertTrue(plain(service.pay(fixture.get(A1), "Anna", "50"))
                .contains("Sending siege points to teammates is turned off in this game mode."));
        assertEquals(100, balance(A1));
        assertEquals(0, balance(A2));
    }

    @Test
    void payMovesPointsToATeammateOnlyAndNeverOverdraws_A01_A03() {
        start(true);
        kill(D1, A1);
        assertTrue(plain(service.pay(fixture.get(A1), "Anna", "50")).contains("Sent 50 siege points to Anna"));
        assertEquals(50, balance(A1));
        assertEquals(50, balance(A2));
        assertTrue(plain(service.pay(fixture.get(A1), "Anna", "999999")).contains("Not enough siege points: 50/999999"));
        assertTrue(plain(service.pay(fixture.get(A1), "Dave", "10")).contains("only send siege points to a teammate"));
        assertTrue(plain(service.pay(fixture.get(A1), "Anna", "ten")).contains("not a whole number"));
        assertEquals(50, balance(A1));
        assertEquals(15, balance(D1));
    }

    @Test
    void theMatchResultCarriesEarnedAndSpent_andTheEndStopsEarning_A04_A11() {
        start(true);
        kill(D1, A1);
        service.pay(fixture.get(A1), "Anna", "30");
        ParticipantResult result = service.withTotals(fixture.match, A1, new ParticipantResult(7, 2, 1, 0, 1, 0));
        assertEquals(100L, result.siegePointsEarned());
        assertEquals(30L, result.siegePointsSpent());

        service.matchEnded(fixture.lobby, fixture.match);
        verify(fixture.get(A1)).sendMessage(any(Component.class));
        kill(D1, A1);
        assertEquals(70, balance(A1), "nothing is earned after the end");

        service.roundReleased(fixture.lobby);
        assertEquals(Optional.empty(), service.economyOf(fixture.match), "balances end with the round (P12)");
        assertNull(service.scoreboardLine(fixture.match, A1));
    }

    @Test
    void aLeaverLosesTheirBalance() {
        start(false);
        kill(D1, A1);
        service.memberLeft(fixture.lobby, A1, 7, "leave", true);
        assertEquals(0, balance(A1));
        assertEquals(100, service.economyOf(fixture.match).orElseThrow().earned(A1), "still in the result");
    }

    @Test
    void outsideARunningMatchTheCommandsSaySo() {
        start(false);
        Player outsider = org.mockito.Mockito.mock(Player.class);
        org.mockito.Mockito.when(outsider.getUniqueId()).thenReturn(new UUID(5, 5));
        assertTrue(plain(service.points(outsider)).contains("only during a running siege match"));
        verify(fixture.get(A2), never()).sendActionBar(any(Component.class));
    }
}
