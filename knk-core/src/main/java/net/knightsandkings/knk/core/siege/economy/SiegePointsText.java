package net.knightsandkings.knk.core.siege.economy;

import net.knightsandkings.knk.core.siege.economy.SiegePointsEconomy.Credit;

import java.util.List;
import java.util.Locale;

/** The plain texts of siege points (DESIGN § 4.1, P18): the action bar after a change and the sidebar line. */
public final class SiegePointsText {

    private SiegePointsText() {
    }

    /**
     * One player's credits of one moment, newest balance last: {@code "+100 kill · 100"},
     * {@code "+130 kill ×1.3 · 830"}, {@code "+3 objective +100 capture · 533"}. Empty for no credits.
     */
    public static String actionBar(List<Credit> credits) {
        if (credits == null || credits.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        for (Credit credit : credits) {
            if (!text.isEmpty()) text.append(' ');
            text.append('+').append(credit.amount()).append(' ').append(credit.source().label());
            if (credit.multiplier() > 1.0 + 1e-9) text.append(" ×").append(multiplier(credit.multiplier()));
        }
        return text.append(" · ").append(credits.get(credits.size() - 1).balanceAfter()).toString();
    }

    /** {@code 1.3}, {@code 1.25}, {@code 1.5}: at most two decimals, no trailing zeros. */
    static String multiplier(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        if (text.contains(".")) text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        return text;
    }

    /** The sidebar line's text after its label. */
    public static String balance(long balance) {
        return Long.toString(balance);
    }
}
