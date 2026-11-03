package net.knightsandkings.knk.core.survival.scaling;

import net.knightsandkings.knk.core.survival.ReadinessIssue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN §7, S21, S29, S30, S54, S55: count, stat multipliers, weights, per-player scaling, caps, preview. */
class WaveScalingTest {
    private static final double EPS = 1e-9;

    private static WaveScalingSettings settings(PerPlayerScaling perPlayer, double statCap, int countCap) {
        return new WaveScalingSettings(ScalingCurve.linear(6, 2.5), ScalingCurve.linear(1, 0.2),
            StatWeights.uniform().with(ScaledStat.WALKING_SPEED, 0.3), perPlayer, 24, statCap, countCap);
    }

    @Test
    void countIsRoundedDownNeverBelowOneAndCapped() {
        WaveScaling scaling = new WaveScaling(settings(PerPlayerScaling.OFF, 10, 0));
        assertEquals(6, scaling.enemyCount(1, 1));
        assertEquals(8, scaling.enemyCount(2, 1), "8.5 rounded down");
        assertEquals(11, scaling.enemyCount(3, 1));

        WaveScaling capped = new WaveScaling(settings(PerPlayerScaling.OFF, 10, 9));
        assertEquals(9, capped.enemyCount(3, 1));

        WaveScaling shrinking = new WaveScaling(new WaveScalingSettings(ScalingCurve.linear(2, -5), ScalingCurve.linear(1, 0),
            StatWeights.uniform(), PerPlayerScaling.OFF, 0, 1, 0));
        assertEquals(1, shrinking.enemyCount(5, 1), "never below 1");
    }

    @Test
    void perPlayerScalingAppliesToCountAndOptionallyToStats() {
        WaveScaling countOnly = new WaveScaling(settings(new PerPlayerScaling(0.5, false), 10, 0));
        assertEquals(6, countOnly.enemyCount(1, 1));
        assertEquals(9, countOnly.enemyCount(1, 2), "6 × (1 + 0.5·1)");
        assertEquals(15, countOnly.enemyCount(1, 4), "6 × 2.5 — ghosts count as players at round start");
        assertEquals(1.0, countOnly.statMultiplier(ScaledStat.HEALTH, 1, 4), 0.0, "stats untouched unless enabled");

        WaveScaling both = new WaveScaling(settings(new PerPlayerScaling(0.5, true), 10, 0));
        assertEquals(1.5, both.statMultiplier(ScaledStat.HEALTH, 1, 2), EPS);
        assertEquals(1.2 * 1.5, both.statMultiplier(ScaledStat.HEALTH, 2, 2), EPS);
    }

    @Test
    void roundOneMultiplierIsExactlyOneAndWeightsScaleTheGrowth() {
        WaveScaling scaling = new WaveScaling(settings(PerPlayerScaling.OFF, 10, 0));
        for (ScaledStat stat : ScaledStat.values()) {
            assertEquals(1.0, scaling.statMultiplier(stat, 1, 1), 0.0, stat.name());
        }
        // Round 6: curve = 1 + 0.2·5 = 2.0; weight 1 → 2.0; walking speed weight 0.3 → 1 + 0.3·1 = 1.3.
        assertEquals(2.0, scaling.statMultiplier(ScaledStat.HEALTH, 6, 1), EPS);
        assertEquals(2.0, scaling.statMultiplier(ScaledStat.MELEE_DAMAGE, 6, 1), EPS);
        assertEquals(2.0, scaling.statMultiplier(ScaledStat.RANGED_DAMAGE, 6, 1), EPS);
        assertEquals(2.0, scaling.statMultiplier(ScaledStat.ATTACK_SPEED, 6, 1), EPS);
        assertEquals(1.3, scaling.statMultiplier(ScaledStat.WALKING_SPEED, 6, 1), EPS);
    }

    @Test
    void statCapAppliesLast() {
        WaveScaling scaling = new WaveScaling(settings(new PerPlayerScaling(1, true), 2.5, 0));
        assertEquals(2.5, scaling.statMultiplier(ScaledStat.HEALTH, 50, 3), EPS);
        assertEquals(2.5, scaling.statMultiplier(ScaledStat.WALKING_SPEED, 50, 3), EPS);
    }

    @Test
    void statCurveBaseIsForcedToOne() {
        WaveScalingSettings s = new WaveScalingSettings(ScalingCurve.linear(6, 1), ScalingCurve.linear(7, 1),
            null, null, 0, 5, 0);
        assertEquals(1.0, s.statCurve().base(), 0.0);
        assertEquals(StatWeights.uniform(), s.statWeights());
        assertEquals(PerPlayerScaling.OFF, s.perPlayer());
    }

    @Test
    void previewListsCountAndMultipliersPerRound() {
        WaveScaling scaling = new WaveScaling(settings(PerPlayerScaling.OFF, 10, 0));
        List<RoundPreview> previews = scaling.preview(1, 3, 2);
        assertEquals(3, previews.size());
        RoundPreview round2 = previews.get(1);
        assertEquals(2, round2.round());
        assertEquals(2, round2.players());
        assertEquals(8, round2.enemyCount());
        assertEquals(1.2, round2.multiplier(ScaledStat.HEALTH), EPS);
        assertEquals(1.06, round2.multiplier(ScaledStat.WALKING_SPEED), EPS);
        assertEquals(5, round2.statMultipliers().size());
        assertEquals(scaling.preview(2, 2), round2);
    }

    @Test
    void readinessFlagsUnusableParameters() {
        List<ReadinessIssue> flat = new WaveScalingSettings(ScalingCurve.linear(0, 0), ScalingCurve.exponential(1, 1),
            null, null, 0, 1, 0).readiness();
        assertTrue(flat.stream().anyMatch(i -> i.code().equals("scaling.count-base") && i.isError()));
        assertTrue(flat.stream().anyMatch(i -> i.code().equals("scaling.count-flat") && !i.isError()));
        assertTrue(flat.stream().anyMatch(i -> i.code().equals("scaling.stat-flat")));
        assertTrue(settings(PerPlayerScaling.OFF, 10, 0).readiness().isEmpty());
        assertTrue(settings(PerPlayerScaling.OFF, 10, 5).readiness().stream().anyMatch(i -> i.code().equals("scaling.count-cap")));
    }

    @Test
    void walkingSpeedLimitFromTheImplementationChecks() {
        assertEquals(Optional.empty(), WalkingSpeedLimit.check(0.23, 1.5));
        ReadinessIssue warning = WalkingSpeedLimit.check(0.23, 2.0).orElseThrow();
        assertFalse(warning.isError());
        ReadinessIssue error = WalkingSpeedLimit.check(0.3, 2.0).orElseThrow();
        assertTrue(error.isError());
        assertEquals(0.5 / 0.23, WalkingSpeedLimit.multiplierAtHardCap(0.23), EPS);
    }

    @Test
    void statWeightsRejectNegatives() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new StatWeights(Map.of(ScaledStat.HEALTH, -1.0)));
    }
}
