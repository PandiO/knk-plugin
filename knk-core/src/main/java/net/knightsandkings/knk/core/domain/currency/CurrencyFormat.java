package net.knightsandkings.knk.core.domain.currency;

import java.text.NumberFormat;
import java.time.Duration;
import java.util.Locale;

import net.knightsandkings.knk.core.domain.users.BalanceCurrency;

/**
 * How currency shows up in chat (currency DESIGN.md §3.6, D6): US digit grouping
 * ({@code 1,234,567}, not v1's German {@code 1.234.567}), singular/plural currency names and short
 * durations for cooldowns and expiries.
 */
public final class CurrencyFormat {

    private CurrencyFormat() {
    }

    /** {@code 1234567} → {@code "1,234,567"}; negative numbers keep their sign. */
    public static String amount(long value) {
        return NumberFormat.getIntegerInstance(Locale.US).format(value);
    }

    /** {@code +1,000} / {@code -250} / {@code 0}. */
    public static String signed(long value) {
        return value > 0 ? "+" + amount(value) : amount(value);
    }

    /** "coin"/"coins", "gem"/"gems", "XP". */
    public static String name(BalanceCurrency currency, long amount) {
        boolean one = Math.abs(amount) == 1;
        return switch (currency) {
            case COINS -> one ? "coin" : "coins";
            case GEMS -> one ? "gem" : "gems";
            case EXPERIENCE -> "XP";
        };
    }

    /** "5s", "3m 20s", "2h 5m", "1d 4h" - two units at most, never negative. */
    public static String duration(Duration duration) {
        long seconds = Math.max(0, duration == null ? 0 : (duration.getSeconds() + (duration.getNano() > 0 ? 1 : 0)));
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3600;
        long minutes = seconds % 3600 / 60;
        long secs = seconds % 60;
        if (days > 0) {
            return hours > 0 ? days + "d " + hours + "h" : days + "d";
        }
        if (hours > 0) {
            return minutes > 0 ? hours + "h " + minutes + "m" : hours + "h";
        }
        if (minutes > 0) {
            return secs > 0 ? minutes + "m " + secs + "s" : minutes + "m";
        }
        return secs + "s";
    }
}
