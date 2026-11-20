package net.knightsandkings.knk.core.production.survey;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One row of a survey report (knk-web-api {@code AreaSurveyUnitDto}): a unit key such as {@code TREE:OAK:LARGE}, how
 * many of them, and how many structures' areas contain them (G7: the API splits the yield equally). The same key may
 * appear once per distinct {@code sharedBy}.
 *
 * @param extra optional JSON with details (water: columns, average depth, open-sky share)
 */
public record SurveyUnit(String unitKey, long count, int sharedBy, String extra) {

    /** The API's limits: upper-case segments joined by {@code :}, letters, digits and {@code _}, at most 100 characters. */
    public static final int MAX_KEY_LENGTH = 100;
    public static final int MAX_ROWS = 2000;
    private static final Pattern VALID = Pattern.compile("[A-Z0-9_]+(:[A-Z0-9_]+)*");

    /** Builds a key from segments, upper-cased, other characters replaced by {@code _}, cut at 100 characters. */
    public static String key(String... segments) {
        StringBuilder key = new StringBuilder();
        for (String segment : segments) {
            if (key.length() > 0) {
                key.append(':');
            }
            String clean = segment == null || segment.isBlank() ? "UNKNOWN"
                    : segment.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
            key.append(clean);
        }
        return key.length() > MAX_KEY_LENGTH ? key.substring(0, MAX_KEY_LENGTH) : key.toString();
    }

    public static boolean isValidKey(String key) {
        return key != null && key.length() <= MAX_KEY_LENGTH && VALID.matcher(key).matches();
    }
}
