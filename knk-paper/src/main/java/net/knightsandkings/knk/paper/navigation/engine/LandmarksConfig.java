package net.knightsandkings.knk.paper.navigation.engine;

import org.bukkit.configuration.ConfigurationSection;

import net.knightsandkings.knk.core.landmarks.LandmarkWeights;

/**
 * {@code navigation.landmarks} in config.yml (off-road navigation phase 5.1, PandiO/knk-workspace#708, DESIGN § 6.5):
 * whether the navigation model loads the landmarks of {@code api/landmarks}, the destination engine offers them
 * ({@code LandmarkProvider}, only while {@code navigation.destination-engine} is on as well) and {@code /knk landmark}
 * works. Phase 5.2 (#709) adds, behind the same flag, the landmarks' preference weights on walk cells in
 * {@code preferred} mode and landmarks as walk-link ends; {@code navigation.preference-max} caps overlapping weights.
 * Default {@code false}: nothing about landmarks runs. Read once at start-up (a change needs a restart).
 *
 * @param enabled       landmarks are loaded, offered, editable in game and weight walk cells
 * @param preferenceMax the cap of overlapping preference weights (and, the other way, its inverse); at least 1.0
 */
public record LandmarksConfig(boolean enabled, double preferenceMax) {

    public static final String KEY = "navigation.landmarks";
    /** Off-road phase 5.2 (#709, DESIGN § 5.4.3). */
    public static final String PREFERENCE_MAX_KEY = "navigation.preference-max";

    public LandmarksConfig {
        preferenceMax = LandmarkWeights.validMax(preferenceMax);
    }

    /** As in phase 5.1: the default cap. */
    public LandmarksConfig(boolean enabled) {
        this(enabled, LandmarkWeights.DEFAULT_MAX);
    }

    public static LandmarksConfig defaults() {
        return new LandmarksConfig(false);
    }

    /** From the root of config.yml; a missing key or section means off; a cap below 1.0 or not a number is 4.0. */
    public static LandmarksConfig from(ConfigurationSection root) {
        return root == null ? defaults() : new LandmarksConfig(root.getBoolean(KEY, false),
            root.getDouble(PREFERENCE_MAX_KEY, LandmarkWeights.DEFAULT_MAX));
    }
}
