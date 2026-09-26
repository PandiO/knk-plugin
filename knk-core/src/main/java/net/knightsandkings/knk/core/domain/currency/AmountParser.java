package net.knightsandkings.knk.core.domain.currency;

import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Parses the amount a player types for /pay (currency DESIGN.md §3.6): digits only, no sign,
 * decimals, exponent or separators, no leading zero, at most 10 digits and at most
 * {@link #MAX_AMOUNT}. v1's /pay accepted "-5000" and stole from the recipient (DESIGN.md §1.1 L1);
 * the server checks the amount again, this only rejects nonsense before a request is made.
 */
public final class AmountParser {

    /** The coin balance cap (knk-web-api BalanceLimits.MaxCoins): no single amount can be larger. */
    public static final long MAX_AMOUNT = 999_999_999L;

    private static final Pattern DIGITS = Pattern.compile("^[1-9][0-9]{0,9}$");

    private AmountParser() {
    }

    /** The amount, or empty for anything that isn't a whole number from 1 to {@link #MAX_AMOUNT}. */
    public static OptionalLong parse(String raw) {
        if (raw == null || !DIGITS.matcher(raw).matches()) {
            return OptionalLong.empty();
        }
        long value = Long.parseLong(raw); // ≤ 10 digits: fits a long
        return value <= MAX_AMOUNT ? OptionalLong.of(value) : OptionalLong.empty();
    }
}
