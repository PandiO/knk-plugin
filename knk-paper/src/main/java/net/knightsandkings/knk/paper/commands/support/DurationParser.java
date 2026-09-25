package net.knightsandkings.knk.paper.commands.support;

import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a single "&lt;amount&gt;&lt;unit&gt;" duration token (e.g. "2h", "90m", "3d") into an
 * expiry instant. Minutes/hours/days are all first-class - a 2-hour tryout rank is exactly as
 * valid an input as a 3-day one, per the developer's explicit "hours minimal" requirement.
 * Used by /knk user group add|remove and perm grant|revoke's optional [duration] argument.
 */
public final class DurationParser {
    private static final Pattern PATTERN = Pattern.compile("^(\\d+)([mhd])$", Pattern.CASE_INSENSITIVE);

    private DurationParser() {}

    /**
     * @param token the raw argument, or null/empty for "no duration given" (permanent).
     * @return the resolved expiry (now + duration), or null if token was null/empty (permanent).
     * @throws IllegalArgumentException if token is non-empty but not a valid "<amount><unit>" form.
     */
    public static OffsetDateTime parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Matcher matcher = PATTERN.matcher(token.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid duration '" + token + "' - expected e.g. 2h, 90m, 3d.");
        }
        long amount = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2).toLowerCase();
        OffsetDateTime now = OffsetDateTime.now();
        return switch (unit) {
            case "m" -> now.plusMinutes(amount);
            case "h" -> now.plusHours(amount);
            case "d" -> now.plusDays(amount);
            default -> throw new IllegalArgumentException("Invalid duration unit '" + unit + "'.");
        };
    }
}
