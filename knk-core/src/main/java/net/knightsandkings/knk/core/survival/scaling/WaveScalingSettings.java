package net.knightsandkings.knk.core.survival.scaling;

import net.knightsandkings.knk.core.survival.ReadinessIssue;

import java.util.ArrayList;
import java.util.List;

/**
 * The difficulty/wave profile's scaling settings (DESIGN §7, §10, S29): snapshot at match start.
 *
 * @param countCurve  the enemy-count curve; its base is the configured round-1 count
 * @param statCurve   the shared stat curve; its base is forced to 1 (S30)
 * @param statWeights per-stat weights (S54)
 * @param perPlayer   per-player scaling (S55)
 * @param maxAlive    max enemies alive at once; 0 = unlimited
 * @param statCap     maximum stat multiplier (applied last); must be at least 1
 * @param countCap    maximum enemies per round; 0 = unlimited
 */
public record WaveScalingSettings(ScalingCurve countCurve, ScalingCurve statCurve, StatWeights statWeights,
                                  PerPlayerScaling perPlayer, int maxAlive, double statCap, int countCap) {

    public WaveScalingSettings {
        if (countCurve == null || statCurve == null) throw new IllegalArgumentException("curves are required");
        if (statWeights == null) statWeights = StatWeights.uniform();
        if (perPlayer == null) perPlayer = PerPlayerScaling.OFF;
        if (maxAlive < 0) throw new IllegalArgumentException("maxAlive must be >= 0 (0 = unlimited)");
        if (countCap < 0) throw new IllegalArgumentException("countCap must be >= 0 (0 = unlimited)");
        if (!(statCap >= 1)) throw new IllegalArgumentException("statCap must be >= 1: " + statCap);
        statCurve = statCurve.asStatCurve();
    }

    /** Readiness (§10): nonzero scaling parameters, a usable round-1 count, caps. Only errors here. */
    public List<ReadinessIssue> readiness() {
        List<ReadinessIssue> issues = new ArrayList<>();
        if (countCurve.base() < 1) {
            issues.add(ReadinessIssue.error("scaling.count-base", "Round-1 enemy count must be at least 1"));
        }
        if (grows(countCurve) == false) {
            issues.add(ReadinessIssue.warning("scaling.count-flat", "The enemy count does not grow with the rounds"));
        }
        if (grows(statCurve) == false) {
            issues.add(ReadinessIssue.warning("scaling.stat-flat", "The enemy stats do not grow with the rounds"));
        }
        if (countCap > 0 && countCap < countCurve.base()) {
            issues.add(ReadinessIssue.error("scaling.count-cap", "Count cap " + countCap + " is below the round-1 count"));
        }
        return List.copyOf(issues);
    }

    private static boolean grows(ScalingCurve curve) {
        return switch (curve.preset()) {
            case LINEAR, LOGARITHMIC -> curve.step() > 0;
            case EXPONENTIAL -> curve.multiplier() > 1;
            case HYBRID -> curve.step() > 0 || curve.multiplier() > 1;
        };
    }
}
