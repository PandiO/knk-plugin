package net.knightsandkings.knk.core.gates;

import java.util.Locale;
import java.util.Set;

/**
 * Words the gate commands read as keywords where a gate structure or door id/name is expected
 * (KNG-78). knk-web-api rejects them as gate structure and door names ({@code GateNameRules}); the
 * plugin warns about names saved before that check ({@link GateManager}).
 */
public final class GateCommandKeywords {
    /** {@code /gate toggle here}: the gate (or door) nearest to the player. */
    public static final String HERE = "here";

    private static final Set<String> RESERVED = Set.of(HERE);

    private GateCommandKeywords() {
    }

    public static boolean isReserved(String name) {
        return name != null && RESERVED.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean isHere(String arg) {
        return arg != null && HERE.equalsIgnoreCase(arg.trim());
    }
}
