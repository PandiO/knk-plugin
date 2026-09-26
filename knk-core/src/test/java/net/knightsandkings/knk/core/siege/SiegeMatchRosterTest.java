package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.KillCredit;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.MemberView;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.SpawnChoice;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static net.knightsandkings.knk.core.siege.SiegeTestData.CONFIG;
import static net.knightsandkings.knk.core.siege.SiegeTestData.player;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 5: match roster - kill/death/streak credit (DESIGN §6.6, N12) and membership (§6.8). */
class SiegeMatchRosterTest {

    private static SiegeMatchRoster roster() {
        Map<Integer, List<java.util.UUID>> teams = new LinkedHashMap<>();
        teams.put(1, List.of(player(1), player(2)));
        teams.put(2, List.of(player(11)));
        teams.put(3, List.of());
        return new SiegeMatchRoster(teams);
    }

    @Test
    void membersPerTeamListsEveryTeamInScenarioOrderIncludingEmptyOnes() {
        SiegeMatchRoster roster = roster();
        assertEquals(List.of(1, 2, 3), List.copyOf(roster.membersPerTeam().keySet()));
        assertEquals(Map.of(1, 2, 2, 1, 3, 0), roster.membersPerTeam());
        assertEquals(OptionalInt.of(2), roster.teamOf(player(11)));
        assertEquals(OptionalInt.empty(), roster.teamOf(player(99)));
    }

    @Test
    void aKillCreditsTheKillerAndGivesTheVictimADeathAndEndsTheirStreak() {
        SiegeMatchRoster roster = roster();
        roster.recordDeath(player(1), player(11), CONFIG);
        roster.recordDeath(player(11), player(1), CONFIG); // 11 dies: its streak (1) ends

        MemberView killer = roster.member(player(1)).orElseThrow();
        MemberView victim = roster.member(player(11)).orElseThrow();
        assertEquals(1, killer.kills());
        assertEquals(1, killer.deaths());
        assertEquals(1, killer.killStreak());
        assertEquals(1, victim.kills());
        assertEquals(1, victim.deaths());
        assertEquals(0, victim.killStreak());
        assertEquals(1, victim.highestKillStreak());
    }

    @Test
    void killMilestonesFollowTheConfiguredThresholds() {
        SiegeMatchRoster roster = roster();
        List<OptionalInt> milestones = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            milestones.add(roster.recordDeath(player(11), player(1), CONFIG).orElseThrow().killsMilestone());
        }
        assertEquals(OptionalInt.of(5), milestones.get(4));
        assertEquals(OptionalInt.of(10), milestones.get(9));
        assertEquals(8, milestones.stream().filter(OptionalInt::isEmpty).count());
    }

    @Test
    void streakAnnouncementCarriesTheStreakNotTheKillTotal_N12() {
        SiegeMatchRoster roster = roster();
        for (int i = 0; i < 6; i++) roster.recordDeath(player(11), player(1), CONFIG); // 6 kills, streak 6
        roster.recordDeath(player(1), player(11), CONFIG);                            // streak ends
        KillCredit credit = null;
        for (int i = 0; i < 4; i++) credit = roster.recordDeath(player(11), player(1), CONFIG).orElseThrow();

        assertEquals(10, credit.kills());
        assertEquals(4, credit.killStreak());
        assertEquals(OptionalInt.of(4), credit.streak(), "streak > 3 is announced with the streak (4), not the kills (10)");
        assertEquals(6, roster.member(player(1)).orElseThrow().highestKillStreak());
    }

    @Test
    void streakThresholdIsStrictlyAboveTheConfiguredValue() {
        SiegeMatchRoster roster = roster();
        KillCredit third = null;
        for (int i = 0; i < 3; i++) third = roster.recordDeath(player(11), player(1), CONFIG).orElseThrow();
        assertEquals(OptionalInt.empty(), third.streak());
        assertEquals(OptionalInt.of(4), roster.recordDeath(player(11), player(1), CONFIG).orElseThrow().streak());
    }

    @Test
    void nullSelfAndNonMemberKillersCreditNothingButTheDeathCounts() {
        SiegeMatchRoster roster = roster();
        assertEquals(Optional.empty(), roster.recordDeath(player(1), null, CONFIG));
        assertEquals(Optional.empty(), roster.recordDeath(player(1), player(1), CONFIG));
        assertEquals(Optional.empty(), roster.recordDeath(player(1), player(99), CONFIG));
        assertEquals(3, roster.member(player(1)).orElseThrow().deaths());
        assertEquals(0, roster.member(player(1)).orElseThrow().kills());
    }

    @Test
    void customThresholdsFromTheConfigurationAreUsed() {
        KnkSiegeConfiguration base = CONFIG;
        KnkSiegeConfiguration custom = new KnkSiegeConfiguration(base.captureAttackBase(), base.captureAttackPerExtra(),
                base.captureAttackPerExtraInstantVictory(), base.captureDefendBase(), base.captureDefendPerExtra(),
                base.captureDefendPerExtraInstantVictory(), base.sideCaptureReduction(), base.voteCloseSecondsBeforeStart(),
                base.drawSecondsBeforeStart(), base.hubSecondsBeforeStart(), base.teamSplitSecondsBeforeStart(),
                base.matchmakingAnnouncementMarks(), List.of(2), 1, base.headshotMultiplier(), base.allowedCommands(),
                base.spawnPickerDelayTicks(), base.enchantDropChancePerMille(), base.allowedEnchantmentKeys(),
                base.enchantLevelMin(), base.enchantLevelMax(), base.maxBooksAlive(), base.nonMemberGateView());
        SiegeMatchRoster roster = roster();
        assertEquals(OptionalInt.empty(), roster.recordDeath(player(11), player(1), custom).orElseThrow().streak());
        KillCredit second = roster.recordDeath(player(11), player(1), custom).orElseThrow();
        assertEquals(OptionalInt.of(2), second.killsMilestone());
        assertEquals(OptionalInt.of(2), second.streak());
    }

    @Test
    void removingAMemberReturnsTheirStatsAndUpdatesTeamCounts() {
        SiegeMatchRoster roster = roster();
        roster.recordCapture(player(11));
        MemberView removed = roster.remove(player(11)).orElseThrow();
        assertEquals(1, removed.captures());
        assertFalse(roster.contains(player(11)));
        assertEquals(0, roster.membersPerTeam().get(2));
        assertEquals(Optional.empty(), roster.remove(player(11)));
    }

    @Test
    void spawnChoiceIsStoredPerMemberAndIgnoredForNonMembers() {
        SiegeMatchRoster roster = roster();
        roster.setSpawnChoice(player(1), SpawnChoice.objective(7));
        roster.setSpawnChoice(player(99), SpawnChoice.spawnpoint(1));
        assertEquals(Optional.of(SpawnChoice.objective(7)), roster.spawnChoice(player(1)));
        assertTrue(roster.spawnChoice(player(2)).isEmpty());
        assertTrue(roster.spawnChoice(player(99)).isEmpty());
    }

    @Test
    void aLostObjectiveResetsItsChoosersToTheirTeamDefaultAndLeavesOthersAlone() {
        SiegeMatchRoster roster = roster();
        roster.setSpawnChoice(player(1), SpawnChoice.objective(7));
        roster.setSpawnChoice(player(2), SpawnChoice.objective(8));
        roster.setSpawnChoice(player(11), SpawnChoice.objective(7)); // team 2: the new holder

        List<java.util.UUID> reset = roster.resetObjectiveChoice(7, 2, teamId -> SpawnChoice.spawnpoint(100 + teamId));

        assertEquals(List.of(player(1)), reset);
        assertEquals(Optional.of(SpawnChoice.spawnpoint(101)), roster.spawnChoice(player(1)));
        assertEquals(Optional.of(SpawnChoice.objective(8)), roster.spawnChoice(player(2)));
        assertEquals(Optional.of(SpawnChoice.objective(7)), roster.spawnChoice(player(11)));
    }

    @Test
    void aLostObjectiveWithoutATeamDefaultClearsTheChoice() {
        SiegeMatchRoster roster = roster();
        roster.setSpawnChoice(player(1), SpawnChoice.objective(7));

        assertEquals(List.of(player(1)), roster.resetObjectiveChoice(7, 2, teamId -> null));
        assertTrue(roster.spawnChoice(player(1)).isEmpty());
    }
}
