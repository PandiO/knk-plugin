package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeLobby;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.siege.SiegeEffect.AnnounceEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.Announcement;
import net.knightsandkings.knk.core.siege.SiegeEffect.Audience;
import net.knightsandkings.knk.core.siege.SiegeEffect.CancelMatchmakingEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.CancelReason;
import net.knightsandkings.knk.core.siege.SiegeEffect.DisableReason;
import net.knightsandkings.knk.core.siege.SiegeEffect.DrawScenarioEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.EndMatchEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.LobbyDisabledEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.MatchmakingStartedEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.PhaseChangedEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.RefreshConfigEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.SendToHubEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.SplitTeamsEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.StartMatchEffect;
import net.knightsandkings.knk.core.siege.SiegeEffect.StartMessageEffect;
import net.knightsandkings.knk.core.siege.SiegeLobbyStateMachine.SkipOutcome;
import net.knightsandkings.knk.core.siege.SiegeLobbyStateMachine.SkipRequester;
import net.knightsandkings.knk.core.siege.SiegeLobbyStateMachine.SkipResult;
import net.knightsandkings.knk.core.siege.VoteTally.VoteChoice;
import net.knightsandkings.knk.core.siege.VoteTally.VoteResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.TreeMap;

import static net.knightsandkings.knk.core.siege.SiegeTestData.CONFIG;
import static net.knightsandkings.knk.core.siege.SiegeTestData.lobby;
import static net.knightsandkings.knk.core.siege.SiegeTestData.player;
import static net.knightsandkings.knk.core.siege.SiegeTestData.twoTeamScenario;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 4: the lobby timeline (DESIGN §5.4, §6.1–6.5) driven tick by tick. */
class SiegeLobbyStateMachineTest {

    private final SiegeRuntimeLocks locks = new SiegeRuntimeLocks();
    private final KnkSiegeScenario cinix = twoTeamScenario(1, 5);
    private final KnkSiegeScenario harbor = twoTeamScenario(2, 6);

    private SiegeLobbyStateMachine machine(KnkSiegeLobby lobby, KnkSiegeConfiguration config) {
        return new SiegeLobbyStateMachine(lobby, config, locks, new Random(3));
    }

    private SiegeLobbyStateMachine defaultMachine() {
        return machine(lobby(1, 300, 900, 2, true, cinix, harbor), CONFIG);
    }

    /** Ticks until the phase changes (or {@code max} ticks); effects keyed by seconds remaining after each tick. */
    private static Map<Integer, List<SiegeEffect>> runMatchmaking(SiegeLobbyStateMachine m, int members) {
        Map<Integer, List<SiegeEffect>> byRemaining = new TreeMap<>();
        int remaining = m.secondsRemaining();
        while (m.phase() == SiegePhase.MATCHMAKING || m.phase() == SiegePhase.HUB) {
            remaining--;
            List<SiegeEffect> effects = m.tick(members);
            if (!effects.isEmpty()) byRemaining.put(remaining, effects);
        }
        return byRemaining;
    }

    private static void ticks(SiegeLobbyStateMachine m, int count, int members) {
        for (int i = 0; i < count; i++) m.tick(members);
    }

    @Test
    void startOpensMatchmakingWithCandidates() {
        SiegeLobbyStateMachine m = defaultMachine();

        List<SiegeEffect> effects = m.start();

        assertEquals(SiegePhase.MATCHMAKING, m.phase());
        assertEquals(300, m.secondsRemaining());
        assertEquals(new PhaseChangedEffect(SiegePhase.DISABLED, SiegePhase.MATCHMAKING), effects.get(0));
        MatchmakingStartedEffect started = assertInstanceOf(MatchmakingStartedEffect.class, effects.get(1));
        assertEquals(2, started.candidates().size());
        assertEquals(new AnnounceEffect(Audience.ONLINE, Announcement.MATCHMAKING_OPENED, 300, true), effects.get(2));
        assertTrue(m.isJoinable());
        assertTrue(m.isVotingOpen());
        assertEquals(20, m.joinCapacity());
    }

    @Test
    void defaultTimelineFiresEveryStepAtItsMark() {
        SiegeLobbyStateMachine m = defaultMachine();
        m.start();

        Map<Integer, List<SiegeEffect>> timeline = runMatchmaking(m, 4);

        // Announcement marks 290/60/30/15 (online), nothing else in between.
        assertEquals(List.of(0, 10, 15, 25, 30, 60, 290), new ArrayList<>(timeline.keySet()));
        assertEquals(List.of(new AnnounceEffect(Audience.ONLINE, Announcement.MATCHMAKING_COUNTDOWN, 290, true)), timeline.get(290));
        assertEquals(List.of(new AnnounceEffect(Audience.ONLINE, Announcement.MATCHMAKING_COUNTDOWN, 60, true)), timeline.get(60));
        // T-30: last countdown mark + voting closes, members hear "hub in 15 seconds" (N8).
        assertEquals(List.of(
                new AnnounceEffect(Audience.ONLINE, Announcement.MATCHMAKING_COUNTDOWN, 30, true),
                new AnnounceEffect(Audience.MEMBERS, Announcement.VOTING_CLOSED, 15, true)), timeline.get(30));
        // T-25: the draw.
        DrawScenarioEffect draw = assertInstanceOf(DrawScenarioEffect.class, timeline.get(25).get(0));
        assertEquals(1, timeline.get(25).size());
        // T-15: joining closes (the countdown says so), HUB, members to the hub.
        assertEquals(List.of(
                new AnnounceEffect(Audience.ONLINE, Announcement.MATCHMAKING_COUNTDOWN, 15, false),
                new PhaseChangedEffect(SiegePhase.MATCHMAKING, SiegePhase.HUB),
                new SendToHubEffect(draw.scenario())), timeline.get(15));
        // T-10: team split.
        assertEquals(List.of(new SplitTeamsEffect(draw.scenario())), timeline.get(10));
        // T-0: the match starts; 4 members × 75 s = 300 s (the scenario minimum).
        assertEquals(List.of(
                new PhaseChangedEffect(SiegePhase.HUB, SiegePhase.IN_PROGRESS),
                new StartMatchEffect(draw.scenario(), 300, 4)), timeline.get(0));

        assertEquals(SiegePhase.IN_PROGRESS, m.phase());
        assertEquals(300, m.secondsRemaining());
        assertEquals(OptionalInt.of(draw.scenario().id()), locks.lockedScenarioOf(1));
    }

    @Test
    void usesTheConfiguredTimelineAndMarks() {
        KnkSiegeConfiguration config = new KnkSiegeConfiguration(5, 2, 5, 6, 3, 6, 0.4,
                40, 35, 20, 5, List.of(100, 50, 20, 500), List.of(5), 3, 1.5, List.of(), 20, 30, List.of(), 1, 2, 10, null);
        SiegeLobbyStateMachine m = machine(lobby(1, 120, 60, 1, false, cinix), config);
        m.start();

        Map<Integer, List<SiegeEffect>> timeline = runMatchmaking(m, 2);

        // 500 is longer than the matchmaking itself and is ignored.
        assertEquals(List.of(0, 5, 20, 35, 40, 50, 100), new ArrayList<>(timeline.keySet()));
        assertEquals(new AnnounceEffect(Audience.MEMBERS, Announcement.VOTING_CLOSED, 20, true), timeline.get(40).get(0));
        assertInstanceOf(DrawScenarioEffect.class, timeline.get(35).get(0));
        assertInstanceOf(SendToHubEffect.class, timeline.get(20).get(2));
        assertInstanceOf(SplitTeamsEffect.class, timeline.get(5).get(0));
        assertInstanceOf(StartMatchEffect.class, timeline.get(0).get(1));
    }

    @Test
    void votingClosesAtTheVoteCloseMark() {
        SiegeLobbyStateMachine m = defaultMachine();
        m.start();
        int candidate = m.candidates().get(0).id();

        ticks(m, 269, 4); // T-31
        assertEquals(VoteResult.CAST, m.vote(player(1), VoteChoice.scenario(candidate)));
        m.removeMember(player(1));
        assertEquals(0, m.voteTally().orElseThrow().totalVotes());
        m.tick(4); // T-30

        assertFalse(m.isVotingOpen());
        assertEquals(VoteResult.VOTING_CLOSED, m.vote(player(2), VoteChoice.scenario(candidate)));
    }

    @Test
    void theVotedScenarioIsDrawn() {
        SiegeLobbyStateMachine m = defaultMachine();
        m.start();
        int second = m.candidates().get(1).id();
        m.vote(player(1), VoteChoice.scenario(second));
        m.vote(player(2), VoteChoice.scenario(second));

        Map<Integer, List<SiegeEffect>> timeline = runMatchmaking(m, 2);

        DrawScenarioEffect draw = (DrawScenarioEffect) timeline.get(25).get(0);
        assertEquals(second, draw.scenario().id());
        assertEquals(VoteTally.DrawMethod.MOST_VOTES, draw.draw().method());
        assertEquals(second, m.drawnScenario().orElseThrow().id());
    }

    @Test
    void notEnoughPlayersAtTheDrawGoesToCooldownAndRecordsNothing() {
        SiegeLobbyStateMachine m = defaultMachine();
        m.start();

        Map<Integer, List<SiegeEffect>> timeline = runMatchmaking(m, 1);

        assertEquals(List.of(
                new CancelMatchmakingEffect(CancelReason.NOT_ENOUGH_PLAYERS, false),
                new PhaseChangedEffect(SiegePhase.MATCHMAKING, SiegePhase.COOLDOWN),
                new RefreshConfigEffect(1)), timeline.get(25));
        assertTrue(timeline.values().stream().flatMap(List::stream).noneMatch(DrawScenarioEffect.class::isInstance),
                "no draw effect, so no SiegeMatch row is created");
        assertEquals(SiegePhase.COOLDOWN, m.phase());
        assertEquals(900, m.secondsRemaining());
        assertEquals(OptionalInt.empty(), locks.lockedScenarioOf(1));

        // After the cooldown, matchmaking starts again.
        ticks(m, 899, 1);
        assertEquals(SiegePhase.COOLDOWN, m.phase());
        List<SiegeEffect> restart = m.tick(1);
        assertEquals(new PhaseChangedEffect(SiegePhase.COOLDOWN, SiegePhase.MATCHMAKING), restart.get(0));
        assertEquals(SiegePhase.MATCHMAKING, m.phase());
    }

    @Test
    void membersLeavingInTheHubCancelTheStart() {
        SiegeLobbyStateMachine m = defaultMachine();
        m.start();
        ticks(m, 290, 3); // T-10, in the hub

        List<SiegeEffect> effects = new ArrayList<>();
        for (int i = 0; i < 10; i++) effects.addAll(m.tick(1));

        assertEquals(List.of(
                new CancelMatchmakingEffect(CancelReason.NOT_ENOUGH_PLAYERS, true),
                new PhaseChangedEffect(SiegePhase.HUB, SiegePhase.COOLDOWN),
                new RefreshConfigEffect(1)), effects);
        assertEquals(OptionalInt.empty(), locks.lockedScenarioOf(1));
    }

    @Test
    void fullLoopThroughTimeoutEndingAndCooldown() {
        SiegeLobbyStateMachine m = defaultMachine();
        m.start();
        runMatchmaking(m, 8);
        assertEquals(600, m.matchDurationSeconds(), "8 members × 75 s");

        assertTrue(m.tick(8).isEmpty());
        assertEquals(List.of(new StartMessageEffect(m.drawnScenario().orElseThrow())), m.tick(8));
        ticks(m, 597, 8);
        assertEquals(SiegePhase.IN_PROGRESS, m.phase());
        assertEquals(1, m.secondsRemaining());

        KnkSiegeScenario scenario = m.drawnScenario().orElseThrow();
        assertEquals(List.of(
                new PhaseChangedEffect(SiegePhase.IN_PROGRESS, SiegePhase.ENDING),
                new EndMatchEffect(scenario, SiegeEndReason.TIME_EXPIRED)), m.tick(8));
        assertTrue(locks.lockedScenarioOf(1).isPresent(), "the lock covers ENDING (gate restore)");

        assertEquals(List.of(
                new PhaseChangedEffect(SiegePhase.ENDING, SiegePhase.COOLDOWN),
                new RefreshConfigEffect(1)), m.tick(8));
        assertEquals(OptionalInt.empty(), locks.lockedScenarioOf(1));
        assertTrue(m.drawnScenario().isEmpty());
    }

    @Test
    void theRuntimeEndsAMatchEarly() {
        SiegeLobbyStateMachine m = defaultMachine();
        assertTrue(m.endMatch(SiegeEndReason.INSTANT_VICTORY).isEmpty(), "no match running yet");
        m.start();
        runMatchmaking(m, 4);

        List<SiegeEffect> effects = m.endMatch(SiegeEndReason.INSTANT_VICTORY);

        assertEquals(SiegePhase.ENDING, m.phase());
        assertEquals(new EndMatchEffect(m.drawnScenario().orElseThrow(), SiegeEndReason.INSTANT_VICTORY), effects.get(1));
        assertTrue(m.endMatch(SiegeEndReason.TEAM_ELIMINATED).isEmpty(), "only once");
    }

    @Test
    void n9SkipNeverThrowsAndSaysWhy() {
        SiegeLobbyStateMachine m = defaultMachine();
        assertEquals(SkipResult.NOT_SKIPPABLE, m.skip(SkipRequester.ADMIN).result(), "disabled");
        m.start();

        // Players may skip the cooldown only.
        assertEquals(SkipResult.NOT_SKIPPABLE, m.skip(SkipRequester.PLAYER).result());

        // Admin: matchmaking jumps to T-31, so the next second closes voting.
        ticks(m, 100, 4);
        SkipOutcome shortened = m.skip(SkipRequester.ADMIN);
        assertEquals(SkipResult.MATCHMAKING_SHORTENED, shortened.result());
        assertTrue(shortened.skipped());
        assertEquals(31, m.secondsRemaining());
        assertEquals(new AnnounceEffect(Audience.MEMBERS, Announcement.VOTING_CLOSED, 15, true), m.tick(4).get(1));
        // Already past T-31: nothing changes (legacy set 31 again, re-running the draw).
        assertEquals(SkipResult.TOO_LATE, m.skip(SkipRequester.ADMIN).result());
        assertEquals(30, m.secondsRemaining());

        ticks(m, 15, 4); // HUB
        assertEquals(SiegePhase.HUB, m.phase());
        assertEquals(SkipResult.TOO_LATE, m.skip(SkipRequester.ADMIN).result());
        assertEquals(SkipResult.NOT_SKIPPABLE, m.skip(SkipRequester.PLAYER).result());

        ticks(m, 15, 4); // IN_PROGRESS - v2 said "already in matchmaking or progress", or NPE'd with no sender
        assertEquals(SiegePhase.IN_PROGRESS, m.phase());
        int remaining = m.secondsRemaining();
        SkipOutcome inMatch = m.skip(SkipRequester.ADMIN);
        assertEquals(SkipResult.NOT_SKIPPABLE, inMatch.result());
        assertTrue(inMatch.effects().isEmpty());
        assertEquals(remaining, m.secondsRemaining());

        m.endMatch(SiegeEndReason.INSTANT_VICTORY);
        m.tick(4); // COOLDOWN
        SkipOutcome cooldown = m.skip(SkipRequester.PLAYER);
        assertEquals(SkipResult.COOLDOWN_SKIPPED, cooldown.result());
        assertEquals(SiegePhase.MATCHMAKING, m.phase());
        assertInstanceOf(MatchmakingStartedEffect.class, cooldown.effects().get(1));
    }

    @Test
    void twoLobbiesNeverRunTheSameScenario() {
        SiegeLobbyStateMachine a = machine(lobby(1, 300, 900, 2, true, cinix), CONFIG);
        SiegeLobbyStateMachine b = machine(lobby(2, 300, 900, 2, true, cinix), CONFIG);
        a.start();
        b.start();

        List<SiegeEffect> bEffects = new ArrayList<>();
        for (int i = 0; i < 275; i++) {
            a.tick(4);
            bEffects.addAll(b.tick(4));
        }

        assertEquals(OptionalInt.of(1), locks.lobbyHoldingScenario(cinix.id()));
        assertEquals(SiegePhase.COOLDOWN, b.phase());
        assertTrue(bEffects.contains(new CancelMatchmakingEffect(CancelReason.NO_SCENARIO_AVAILABLE, false)));

        // While lobby 1 holds it, lobby 2 can't even open matchmaking with it.
        List<SiegeEffect> retry = b.skip(SkipRequester.ADMIN).effects();
        assertTrue(retry.contains(new CancelMatchmakingEffect(CancelReason.NO_SCENARIO_AVAILABLE, false)));
        assertEquals(SiegePhase.COOLDOWN, b.phase());
    }

    @Test
    void twoLobbiesNeverRunTwoScenariosOfTheSameTown() {
        KnkSiegeScenario otherCinix = twoTeamScenario(3, 5);
        SiegeLobbyStateMachine a = machine(lobby(1, 300, 900, 1, true, cinix), CONFIG);
        SiegeLobbyStateMachine b = machine(lobby(2, 300, 900, 1, true, otherCinix, harbor), CONFIG);
        a.start();
        b.start();
        for (int i = 0; i < 275; i++) {
            a.tick(4);
            b.tick(4);
        }

        assertEquals(OptionalInt.of(cinix.id()), locks.lockedScenarioOf(1));
        // Lobby 2 either drew the harbour, or lost its Cinix candidate to the town lock and fell back to it.
        assertEquals(OptionalInt.of(harbor.id()), locks.lockedScenarioOf(2));
        assertEquals(SiegePhase.MATCHMAKING, b.phase());
    }

    @Test
    void aLobbyWithoutReadyScenariosStaysDisabled() {
        SiegeLobbyStateMachine m = machine(lobby(1, 300, 900, 2, true), CONFIG);

        List<SiegeEffect> effects = m.start();

        assertEquals(List.of(new LobbyDisabledEffect(DisableReason.NO_READY_SCENARIO)), effects);
        assertEquals(SiegePhase.DISABLED, m.phase());
        assertTrue(m.tick(0).isEmpty());
    }

    @Test
    void configurationIsFrozenDuringAMatchAndAppliedAtCooldown() {
        SiegeLobbyStateMachine m = defaultMachine();
        m.start();
        runMatchmaking(m, 4);
        KnkSiegeLobby shorterCooldown = lobby(1, 300, 60, 2, true, cinix, harbor);

        assertFalse(m.offerConfiguration(shorterCooldown, CONFIG));
        assertTrue(m.hasPendingConfiguration());
        assertEquals(900, m.lobby().cooldownSeconds());

        m.endMatch(SiegeEndReason.INSTANT_VICTORY);
        m.tick(4);

        assertEquals(SiegePhase.COOLDOWN, m.phase());
        assertFalse(m.hasPendingConfiguration());
        assertEquals(60, m.secondsRemaining());
        // Between matches an offer applies at once.
        assertTrue(m.offerConfiguration(lobby(1, 300, 30, 2, true, cinix), CONFIG));
        assertEquals(30, m.lobby().cooldownSeconds());
    }

    @Test
    void invalidConfigurationIsRejected() {
        KnkSiegeConfiguration badTimeline = new KnkSiegeConfiguration(5, 2, 5, 6, 3, 6, 0.4,
                10, 25, 15, 10, List.of(), List.of(), 3, 1.5, List.of(), 20, 30, List.of(), 1, 2, 10, null);
        SiegeLobbyStateMachine m = defaultMachine();

        assertThrows(IllegalArgumentException.class, () -> m.offerConfiguration(lobby(1, 300, 900, 2, true, cinix), badTimeline));
        assertThrows(IllegalArgumentException.class, () -> m.offerConfiguration(lobby(1, 30, 900, 2, true, cinix), CONFIG));
        assertThrows(IllegalArgumentException.class, () -> m.offerConfiguration(lobby(2, 300, 900, 2, true, cinix), CONFIG));
        assertThrows(IllegalArgumentException.class, () -> machine(lobby(1, 300, 900, 2, true, cinix), badTimeline));
        assertEquals(CONFIG, m.configuration());
    }

    @Test
    void stopAbortsARunningMatchAndDisablesTheLobby() {
        SiegeLobbyStateMachine m = defaultMachine();
        m.start();
        runMatchmaking(m, 4);
        KnkSiegeScenario scenario = m.drawnScenario().orElseThrow();

        List<SiegeEffect> effects = m.stop(SiegeEndReason.ADMIN_STOPPED);

        assertEquals(List.of(
                new EndMatchEffect(scenario, SiegeEndReason.ADMIN_STOPPED),
                new PhaseChangedEffect(SiegePhase.IN_PROGRESS, SiegePhase.DISABLED),
                new LobbyDisabledEffect(DisableReason.STOPPED)), effects);
        assertEquals(OptionalInt.empty(), locks.lockedScenarioOf(1));
        assertThrows(IllegalArgumentException.class, () -> m.stop(SiegeEndReason.TIME_EXPIRED));
        assertTrue(m.stop(SiegeEndReason.SERVER_RESTART).isEmpty(), "already disabled");
    }

    @Test
    void stopDuringTheHubRestoresMembers() {
        SiegeLobbyStateMachine m = defaultMachine();
        m.start();
        ticks(m, 290, 4);

        List<SiegeEffect> effects = m.stop(SiegeEndReason.SERVER_RESTART);

        assertEquals(new CancelMatchmakingEffect(CancelReason.SERVER_RESTART, true), effects.get(0));
        assertEquals(SiegePhase.DISABLED, m.phase());
    }

    @Test
    void joinRequirementsFollowTheCandidatesThenTheDrawnScenario() {
        KnkSiegeScenario veteran = new KnkSiegeScenario(7, "Veteran", null, 8, "Town 8", null, null, null,
                2, 40, 3, 1000, null, null, true, false, true, cinix.teams(), cinix.objectives(), null);
        KnkSiegeScenario open = new KnkSiegeScenario(8, "Open", null, 9, "Town 9", null, null, null,
                2, 10, null, null, null, null, true, false, true, cinix.teams(), cinix.objectives(), null);
        SiegeLobbyStateMachine m = machine(lobby(1, 300, 900, 2, true, veteran, open), CONFIG);
        m.start();

        assertEquals(40, m.joinCapacity());
        assertEquals(0, m.joinMinTitleExperience());

        m.vote(player(1), VoteChoice.scenario(7));
        ticks(m, 275, 4);

        assertEquals(7, m.drawnScenario().orElseThrow().id());
        assertEquals(40, m.joinCapacity());
        assertEquals(1000, m.joinMinTitleExperience());
    }
}
