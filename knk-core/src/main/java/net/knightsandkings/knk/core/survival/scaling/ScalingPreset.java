package net.knightsandkings.knk.core.survival.scaling;

/** The shared growth presets for enemy count and stats (DESIGN §7, S29). */
public enum ScalingPreset {
    /** {@code b + a·(r−1)}: constant growth per round. */
    LINEAR,
    /** {@code b + a·ln(r)}: fast early, flattening later. */
    LOGARITHMIC,
    /** {@code b · m^(r−1)}: slow early, accelerating. */
    EXPONENTIAL,
    /** {@code b + a·(r−1)} up to the switch round s, then {@code (b + a·(s−1)) · m^(r−s)} (CoD-style). */
    HYBRID
}
