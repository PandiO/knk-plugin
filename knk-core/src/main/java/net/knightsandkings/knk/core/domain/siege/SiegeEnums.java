package net.knightsandkings.knk.core.domain.siege;

import java.util.Locale;

/**
 * Conversions between the API's PascalCase string enums ({@code "PreLockdownView"}) and the
 * plugin's UPPER_SNAKE constants ({@code PRE_LOCKDOWN_VIEW}).
 */
final class SiegeEnums {
    private SiegeEnums() {}

    static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) return fallback;
        String wanted = value.replace("_", "").trim().toLowerCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().replace("_", "").toLowerCase(Locale.ROOT).equals(wanted)) {
                return constant;
            }
        }
        return fallback;
    }

    static String toApiName(Enum<?> constant) {
        StringBuilder out = new StringBuilder();
        for (String part : constant.name().split("_")) {
            if (part.isEmpty()) continue;
            out.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }
}
