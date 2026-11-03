package net.knightsandkings.knk.core.survival.scaling;

/**
 * One growth curve of the §7 presets table (S29). Round r starts at 1; {@code value(1)} is always {@code base}.
 *
 * @param preset      the shape
 * @param base        b: the round-1 value (the round-1 enemy count, or exactly 1 for a stat curve)
 * @param step        a: per-round step (linear, logarithmic, hybrid before the switch); ignored by exponential
 * @param multiplier  m: per-round factor (exponential, hybrid after the switch); ignored by linear/logarithmic
 * @param switchRound s: the hybrid switch round (at least 1); ignored by the other presets
 */
public record ScalingCurve(ScalingPreset preset, double base, double step, double multiplier, int switchRound) {

    public ScalingCurve {
        if (preset == null) throw new IllegalArgumentException("preset is required");
        if (!Double.isFinite(base) || !Double.isFinite(step) || !Double.isFinite(multiplier)) {
            throw new IllegalArgumentException("curve parameters must be finite");
        }
        if (switchRound < 1) throw new IllegalArgumentException("switchRound must be >= 1: " + switchRound);
    }

    public static ScalingCurve linear(double base, double step) {
        return new ScalingCurve(ScalingPreset.LINEAR, base, step, 1, 1);
    }

    public static ScalingCurve logarithmic(double base, double step) {
        return new ScalingCurve(ScalingPreset.LOGARITHMIC, base, step, 1, 1);
    }

    public static ScalingCurve exponential(double base, double multiplier) {
        return new ScalingCurve(ScalingPreset.EXPONENTIAL, base, 0, multiplier, 1);
    }

    public static ScalingCurve hybrid(double base, double step, int switchRound, double multiplier) {
        return new ScalingCurve(ScalingPreset.HYBRID, base, step, multiplier, switchRound);
    }

    /** A stat curve has b = 1 so round 1 is vanilla (S30): the multiplier is exactly 1 there. */
    public ScalingCurve asStatCurve() {
        return new ScalingCurve(preset, 1, step, multiplier, switchRound);
    }

    /** The curve's value in round {@code round} (1-based). */
    public double value(int round) {
        if (round < 1) throw new IllegalArgumentException("round must be >= 1: " + round);
        return switch (preset) {
            case LINEAR -> base + step * (round - 1);
            case LOGARITHMIC -> base + step * Math.log(round);
            case EXPONENTIAL -> base * Math.pow(multiplier, round - 1);
            case HYBRID -> round <= switchRound
                ? base + step * (round - 1)
                : (base + step * (switchRound - 1)) * Math.pow(multiplier, round - switchRound);
        };
    }
}
