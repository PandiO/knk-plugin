package net.knightsandkings.knk.core.menu;

import java.util.Locale;

/**
 * Parses knk-web-api's PascalCase enum-name strings (e.g. "ContentGrid",
 * "OnDirty") into this module's SCREAMING_SNAKE_CASE enum constants (e.g.
 * {@code CONTENT_GRID}, {@code ON_DIRTY}). Phase 1's domain records keep
 * these fields as plain {@code String} by design (see {@code KnkMenuTemplate}'s
 * javadoc); parsing them is explicitly this phase's job.
 * <p>
 * Comparison is done by stripping underscores and upper-casing both sides,
 * so it's tolerant of the exact casing/delimiter convention on either end
 * without needing a hand-written mapping table per enum.
 */
final class MenuEnumParsing {

    private MenuEnumParsing() {
    }

    /**
     * @param defaultIfNull value to use when {@code raw} itself is null - matching
     *                      the persisted schema's own column default, not an error.
     *                      A non-null {@code raw} that doesn't match any constant is
     *                      always a hard error: that's a real data bug (e.g. a typo),
     *                      not an absent value.
     */
    static <E extends Enum<E>> E parse(Class<E> type, String raw, E defaultIfNull, String fieldName, String context) {
        if (raw == null) {
            return defaultIfNull;
        }

        String normalized = normalize(raw);
        for (E constant : type.getEnumConstants()) {
            if (normalize(constant.name()).equals(normalized)) {
                return constant;
            }
        }

        throw new MenuAssemblyException(
                "Unrecognized " + fieldName + " value " + quoted(raw) + " for " + type.getSimpleName()
                        + " in " + context
        );
    }

    private static String normalize(String value) {
        return value.replace("_", "").toUpperCase(Locale.ROOT);
    }

    private static String quoted(String raw) {
        return raw == null ? "null" : "\"" + raw + "\"";
    }
}
