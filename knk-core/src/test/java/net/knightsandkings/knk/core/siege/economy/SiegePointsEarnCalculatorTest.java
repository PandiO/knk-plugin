package net.knightsandkings.knk.core.siege.economy;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.siege.AllianceResolver;
import net.knightsandkings.knk.core.siege.economy.SiegePointsEarnCalculator.Award;
import net.knightsandkings.knk.core.siege.economy.SiegePointsEarnCalculator.Death;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Siege shop § 5.2 with the default amounts (PandiO/knk-workspace#866): A05 kills and the capped streak, A06 assists
 * (with {@link AssistTracker}), A07 deaths, friendly fire and suicides, A08 gate damage, A09 the active-only trickle.
 * Capture progress and captures are in {@code SiegePointsCaptureTest} (they need the objective board).
 */
class SiegePointsEarnCalculatorTest {

    private static final int DEFENDERS = 1;
    private static final int ATTACKERS = 2;
    private static final int ATTACKER_ALLY = 3; // a second attacking team in the attackers' alliance

    private static final UUID A1 = new UUID(0, 1);
    private static final UUID A2 = new UUID(0, 2);
    private static final UUID A3 = new UUID(0, 3);
    private static final UUID D1 = new UUID(0, 11);
    private static final UUID D2 = new UUID(0, 12);
    private static final UUID OUTSIDER = new UUID(0, 99);

    private final AllianceResolver alliances = new AllianceResolver(List.of(
            team(DEFENDERS, SiegeTeamRole.DEFENDER, 1), team(ATTACKERS, SiegeTeamRole.ATTACKER, 2),
            team(ATTACKER_ALLY, SiegeTeamRole.ATTACKER, 2)));
    private final Map<UUID, Integer> teams = new HashMap<>(Map.of(A1, ATTACKERS, A2, ATTACKERS, A3, ATTACKER_ALLY,
            D1, DEFENDERS, D2, DEFENDERS));
    private final ToIntFunction<UUID> teamOf = id -> teams.getOrDefault(id, -1);
    private final SiegePointsEarnCalculator calculator = new SiegePointsEarnCalculator(SiegePointsSettings.defaults());

    private static KnkSiegeTeam team(int id, SiegeTeamRole role, int alliance) {
        return new KnkSiegeTeam(id, id, role, alliance, null, "Team " + id, "WHITE", null, null, List.of());
    }

    private List<Award> death(UUID victim, UUID killer, int streak, UUID... assisters) {
        return calculator.death(new Death(victim, killer, streak, List.of(assisters)), teamOf, alliances);
    }

    private static Award award(UUID player, SiegePointsSource source, long amount) {
        return new Award(player, source, amount, 1.0);
    }

    // ---- A05: kill 100, streak 110..150, capped, reset with the streak ----

    @Test
    void aKillPays100AndTheStreakRaisesItByATenthUpToTheCap() {
        assertEquals(100, calculator.killPoints(1));
        assertEquals(List.of(110L, 120L, 130L, 140L, 150L, 150L, 150L),
                java.util.stream.IntStream.rangeClosed(2, 8).mapToObj(calculator::killPoints).toList());
        assertEquals(1.0, calculator.streakMultiplier(0));
        assertEquals(1.5, calculator.streakMultiplier(20), 1e-9);
    }

    @Test
    void theKillAwardCarriesTheMultiplierAndTheVictimGetsTheDeathAmount() {
        List<Award> awards = death(D1, A1, 4);
        assertEquals(2, awards.size());
        assertEquals(A1, awards.get(0).player());
        assertEquals(SiegePointsSource.KILL, awards.get(0).source());
        assertEquals(130, awards.get(0).amount());
        assertEquals(1.3, awards.get(0).multiplier(), 1e-9);
        assertEquals(award(D1, SiegePointsSource.DEATH, 15), awards.get(1));
    }

    @Test
    void aStreakResetByDeathPaysTheBaseKillAgain() {
        // The roster resets the killer's streak when they die; the next kill is the 1st of a new streak.
        assertEquals(100, death(D1, A1, 1).get(0).amount());
    }

    @Test
    void weaponKillsCountLikeAnyKill_W60() {
        // A siege shot credits the shooter (W69): the same death event with the shooter as killer.
        assertEquals(150, death(D2, A1, 6).get(0).amount());
    }

    // ---- A07: deaths, friendly fire, suicide ----

    @Test
    void anEnvironmentDeathPaysTheVictimTheDeathAmount() {
        assertEquals(List.of(award(A1, SiegePointsSource.DEATH, 15)), death(A1, null, 0));
    }

    @Test
    void friendlyFireAndSuicidePayNobody() {
        assertEquals(List.of(), death(A1, A2, 1), "same team");
        assertEquals(List.of(), death(A1, A3, 1), "allied team");
        assertEquals(List.of(), death(A1, A1, 0), "suicide");
        assertEquals(List.of(), death(OUTSIDER, A1, 1), "a non-member victim");
    }

    @Test
    void aNonMembersKillCountsAsTheEnvironment() {
        assertEquals(List.of(award(D1, SiegePointsSource.DEATH, 15)), death(D1, OUTSIDER, 1));
    }

    // ---- A06: assists ----

    @Test
    void anAssistPays40ToEveryEnemyAssisterButNeverTheKiller() {
        List<Award> awards = death(D1, A1, 1, A2, A3, A1, D2);
        assertEquals(List.of(new Award(A1, SiegePointsSource.KILL, 100, 1.0), award(D1, SiegePointsSource.DEATH, 15),
                award(A2, SiegePointsSource.ASSIST, 40), award(A3, SiegePointsSource.ASSIST, 40)), awards);
    }

    @Test
    void assistsNeedFourDamageWithinTenSeconds() {
        AssistTracker tracker = AssistTracker.of(SiegePointsSettings.defaults());
        long t = 1_000_000L;
        tracker.recordDamage(D1, A2, 2.0, t);
        tracker.recordDamage(D1, A2, 2.0, t + 3_000);   // 2 hearts in total
        tracker.recordDamage(D1, A3, 1.0, t + 4_000);   // half a heart only
        tracker.recordDamage(D1, A1, 6.0, t + 5_000);   // the killer
        assertEquals(List.of(A2), tracker.assistersOnDeath(D1, A1, t + 9_000));
        assertEquals(0, tracker.trackedVictims(), "the victim's log is cleared on death");

        tracker.recordDamage(D1, A2, 4.0, t);
        assertEquals(List.of(), tracker.assistersOnDeath(D1, A1, t + 15_000), "15 s ago: outside the window");

        tracker.recordDamage(D1, A2, 3.0, t);
        tracker.recordDamage(D1, A2, 3.0, t + 12_000);
        assertEquals(List.of(), tracker.assistersOnDeath(D1, A1, t + 12_500), "only the last 10 s count");

        tracker.recordDamage(D1, D1, 10.0, t);
        tracker.recordDamage(D1, A2, -1.0, t);
        assertEquals(List.of(), tracker.assistersOnDeath(D1, null, t), "self-hits and negative damage are ignored");
    }

    // ---- A08: gate damage ----

    @Test
    void gateDamagePaysOnePointPerFiveHpAndKeepsTheFractions() {
        assertEquals(List.of(award(A1, SiegePointsSource.GATE_DAMAGE, 10)), calculator.gateDamage(A1, 50));
        assertEquals(List.of(), calculator.gateDamage(A1, 3));
        assertEquals(List.of(award(A1, SiegePointsSource.GATE_DAMAGE, 1)), calculator.gateDamage(A1, 2.5), "3 + 2.5 HP");
        assertEquals(List.of(), calculator.gateDamage(A1, Double.NaN));
        SiegePointsEarnCalculator off = new SiegePointsEarnCalculator(new SiegePointsSettings(100, 0.1, 1.5, 40, 4, 10, 15,
                0, 3, 100, 10, 60, false));
        assertEquals(List.of(), off.gateDamage(A1, 500), "gateHpPerPoint 0: gate damage pays nothing");
    }

    // ---- A09: the trickle pays active members only ----

    @Test
    void theTricklePaysOnlyMembersActiveInTheInterval() {
        List<UUID> members = List.of(A1, A2, D1, D2);
        for (int s = 1; s < 60; s++) {
            if (s == 20) calculator.markActive(A1);              // fought
            if (s == 30) calculator.recordTravel(D1, 4);         // walked 4 + 7 blocks
            if (s == 31) calculator.recordTravel(D1, 7);
            if (s == 40) calculator.recordTravel(D2, 9.5);       // 9.5 blocks: not enough
            assertEquals(List.of(), calculator.secondTicked(members));
        }
        assertEquals(List.of(award(A1, SiegePointsSource.TRICKLE, 10), award(D1, SiegePointsSource.TRICKLE, 10)),
                calculator.secondTicked(members), "A2 stood still: nothing");
        for (int s = 1; s < 60; s++) calculator.secondTicked(members);
        assertEquals(List.of(), calculator.secondTicked(members), "activity starts over each interval");
    }

    @Test
    void aKillAndGateDamageCountAsActivity() {
        death(D1, A2, 1);
        calculator.gateDamage(D2, 1);
        List<Award> awards = List.of();
        for (int s = 0; s < 60; s++) awards = calculator.secondTicked(List.of(A2, D2, A1));
        assertEquals(List.of(award(A2, SiegePointsSource.TRICKLE, 10), award(D2, SiegePointsSource.TRICKLE, 10)), awards);
    }

    @Test
    void settingsClampNonsense() {
        SiegePointsSettings s = new SiegePointsSettings(-1, Double.NaN, 0.5, -1, -2, -3, -4, -5, -6, -7, -8, 0, true);
        assertEquals(0, s.pointsPerKill());
        assertEquals(0, s.streakStep());
        assertEquals(1.0, s.streakCap());
        assertEquals(1, s.trickleIntervalSeconds());
        assertTrue(s.transfersEnabled());
        assertEquals(SiegePointsSettings.defaults(), SiegePointsSettings.defaults().withTransfersEnabled(true).withTransfersEnabled(false));
    }
}
