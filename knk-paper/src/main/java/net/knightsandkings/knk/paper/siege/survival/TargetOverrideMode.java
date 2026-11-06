package net.knightsandkings.knk.paper.siege.survival;

import java.util.Locale;

/** The per-objective / per-gate NPC targeting override (DESIGN §4 "eligibility overrides"; API {@code Standard|Allow|Deny}). */
public enum TargetOverrideMode {
    STANDARD, ALLOW, DENY;

    public static TargetOverrideMode parse(String value) {
        if (value == null) return STANDARD;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "allow" -> ALLOW;
            case "deny" -> DENY;
            default -> STANDARD;
        };
    }
}
