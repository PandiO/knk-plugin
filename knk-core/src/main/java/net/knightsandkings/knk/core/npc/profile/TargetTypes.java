package net.knightsandkings.knk.core.npc.profile;

import java.util.Locale;

/**
 * Target-type keys a {@link CombatProfile} may override per target. Plain strings so every consumer
 * can add its own ({@code "distractor"}, {@code "villager"}); these are the ones the Siege Survival
 * design names (DESIGN §4).
 */
public final class TargetTypes {
    public static final String PLAYER = "player";
    public static final String OBJECTIVE = "objective";
    public static final String GATE = "gate";
    /** Used when the adapter has no target type (no override applies). */
    public static final String NONE = "";

    private TargetTypes() {}

    public static String normalize(String targetType) {
        return targetType == null ? NONE : targetType.trim().toLowerCase(Locale.ROOT);
    }
}
