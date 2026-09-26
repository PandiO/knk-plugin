package net.knightsandkings.knk.paper.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.knightsandkings.knk.core.domain.users.RewardMultiplier;
import net.knightsandkings.knk.core.domain.users.SalaryPayoutResult;
import net.knightsandkings.knk.core.domain.users.TitleChangeResult;
import net.knightsandkings.knk.paper.utils.LegacyStyles;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;

/**
 * Builds every "you received coins/gems/XP" message (KNG-16) in one shape, so the player sees
 * what they got and why:
 * <pre>
 *   Salary (Knight): 975 ×2 Personal ×1.2 Royal = +2,340 coins
 *     4,800 coins/h × 1.5h paid for 2.0h away
 *   Title bonus: 13,500 ×2 Personal ×1.2 Royal = +32,400 coins
 * </pre>
 * The base amount comes first, then each multiplier with its reason - "Server" (global),
 * "Personal", or the rank's name in that rank's chat color, as chat shows it (KNG-7) - and the
 * total that was actually credited. Multipliers of exactly 1 are left out, so a player without
 * any gets just {@code Salary (Serf): +650 coins}. Totals are the API's rounded amounts, so a
 * total can differ from base × multipliers by a coin or two.
 *
 * <p>Kept free of Bukkit types so it is unit testable, like {@link ChatLineFormat}.
 */
public final class RewardMessageFormat {
    private RewardMessageFormat() {}

    public enum Currency {
        COINS("coins", NamedTextColor.GOLD),
        GEMS("gems", NamedTextColor.AQUA),
        XP("XP", NamedTextColor.LIGHT_PURPLE);

        private final String label;
        private final TextColor color;

        Currency(String label, TextColor color) {
            this.label = label;
            this.color = color;
        }
    }

    private static final Style REASON = Style.style(NamedTextColor.YELLOW);
    private static final Style NUMBER = Style.style(NamedTextColor.WHITE);
    private static final Style DETAIL = Style.style(NamedTextColor.GRAY);
    /** A rank without chat colors of its own (e.g. Default). */
    private static final Style RANK_FALLBACK = Style.style(NamedTextColor.YELLOW);

    /** A gap payout (offline time, a restart) is anything noticeably longer than an hour. */
    private static final double GAP_HOURS = 1.5;

    /**
     * One reward line: {@code <reason>: <base> ×<m> <why> … = +<total> <currency>}, or
     * {@code <reason>: +<total> <currency>} when no multiplier other than 1 applies.
     */
    public static Component line(String reason, Currency currency, double base, List<RewardMultiplier> multipliers, int total) {
        List<RewardMultiplier> shown = multipliers == null ? List.of()
                : multipliers.stream().filter(m -> !isNeutral(m.value())).toList();

        TextComponent.Builder line = Component.text()
                .append(Component.text(reason + ": ", REASON));
        if (!shown.isEmpty()) {
            line.append(Component.text(formatAmount(base), NUMBER));
            for (RewardMultiplier multiplier : shown) {
                line.append(Component.text(" ×" + formatMultiplier(multiplier.value()) + " ", NUMBER))
                    .append(multiplierLabel(multiplier));
            }
            line.append(Component.text(" = ", DETAIL));
        }
        line.append(Component.text("+" + formatAmount(total) + " " + currency.label, Style.style(currency.color)));
        return line.build();
    }

    /**
     * The salary payout message, personalized with the player's title. A gap payout gets a
     * second line saying how the hours away were counted (log decay, DESIGN.md §5).
     *
     * @param titleName the player's current title, or null if unknown
     */
    public static List<Component> salary(SalaryPayoutResult result, String titleName) {
        String reason = titleName == null || titleName.isBlank() ? "Salary" : "Salary (" + titleName + ")";
        // An API that predates the breakdown sends no base; show just the total then.
        boolean hasBreakdown = result.baseAmount() > 0;
        double base = hasBreakdown ? result.baseAmount() : result.amountPaid();

        List<Component> lines = new ArrayList<>();
        lines.add(line(reason, Currency.COINS, base, hasBreakdown ? result.multipliers() : List.of(), result.amountPaid()));
        if (result.hoursCovered() >= GAP_HOURS && hasBreakdown && result.titleSalary() > 0) {
            lines.add(Component.text("  " + formatAmount(result.titleSalary()) + " coins/h × "
                    + formatHours(result.paidHours()) + "h paid for " + formatHours(result.hoursCovered()) + "h away", DETAIL));
        }
        return lines;
    }

    /** One line per title promotion bonus that paid anything: coins, then gems, then XP. */
    public static List<Component> titleBonuses(TitleChangeResult change) {
        List<Component> lines = new ArrayList<>();
        addBonus(lines, Currency.COINS, change.coinBonusBase(), change.coinBonusMultipliers(), change.coinBonusGranted());
        addBonus(lines, Currency.GEMS, change.gemBonusBase(), change.gemBonusMultipliers(), change.gemBonusGranted());
        addBonus(lines, Currency.XP, change.expBonusBase(), change.expBonusMultipliers(), change.expBonusGranted());
        return lines;
    }

    private static void addBonus(List<Component> lines, Currency currency, int base, List<RewardMultiplier> multipliers, int granted) {
        if (granted <= 0) {
            return;
        }
        boolean hasBreakdown = base > 0;
        lines.add(line("Title bonus", currency, hasBreakdown ? base : granted, hasBreakdown ? multipliers : List.of(), granted));
    }

    private static Component multiplierLabel(RewardMultiplier multiplier) {
        String source = multiplier.source() == null ? "" : multiplier.source();
        return switch (source) {
            case RewardMultiplier.SOURCE_GLOBAL -> Component.text("Server", DETAIL);
            case RewardMultiplier.SOURCE_RANK -> Component.text(
                    multiplier.name() == null || multiplier.name().isBlank() ? "Rank" : multiplier.name(),
                    LegacyStyles.parse(multiplier.chatPrimaryColor(), RANK_FALLBACK));
            default -> Component.text("Personal", DETAIL);
        };
    }

    private static boolean isNeutral(double value) {
        return Math.abs(value - 1.0) < 0.0005;
    }

    /** Whole amounts with thousands separators: 32400 -> "32,400". */
    static String formatAmount(double amount) {
        return String.format(Locale.ROOT, "%,d", Math.round(amount));
    }

    /** Up to two decimals, trailing zeros dropped: 2.0 -> "2", 1.2 -> "1.2", 1.15 -> "1.15". */
    static String formatMultiplier(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        return text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
    }

    static String formatHours(double hours) {
        return String.format(Locale.ROOT, "%.1f", hours);
    }
}
