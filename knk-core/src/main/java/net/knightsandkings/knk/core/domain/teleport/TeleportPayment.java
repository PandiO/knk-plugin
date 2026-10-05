package net.knightsandkings.knk.core.domain.teleport;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * One currency a teleport charge took or a refund gave back (knk-web-api {@code TeleportPaymentDto},
 * Linear KNG-41: a permission group's fixed price can combine coins, gems and XP in one charge).
 *
 * @param currency   "Coins", "Gems" or "Experience"
 * @param amount     how much (positive)
 * @param newBalance the player's balance of that currency afterwards
 */
public record TeleportPayment(String currency, long amount, long newBalance) {

    public TeleportPayment {
        Objects.requireNonNull(currency, "currency must not be null");
    }

    /** "coins", "gems" or "XP" - the word players see after an amount. */
    public static String word(String currency) {
        if (currency == null) {
            return "";
        }
        return switch (currency.toLowerCase(Locale.ROOT)) {
            case "coins" -> "coins";
            case "gems" -> "gems";
            case "experience", "xp" -> "XP";
            default -> currency.toLowerCase(Locale.ROOT);
        };
    }

    /** {@code amount} followed by its word, singular for 1 ("1 gem", "100 coins", "50 XP"). */
    public static String amount(long amount, String currency) {
        String word = word(currency);
        if (amount == 1 && word.endsWith("s")) {
            word = word.substring(0, word.length() - 1);
        }
        return amount + " " + word;
    }

    /** "100 coins", "100 coins and 1 gem", "100 coins, 1 gem and 50 XP". */
    public static String describe(List<String> parts) {
        if (parts.isEmpty()) {
            return "";
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    /** What these payments took ("100 coins and 1 gem"). */
    public static String describeAmounts(List<TeleportPayment> payments) {
        return describe(payments.stream().map(p -> amount(p.amount(), p.currency())).collect(Collectors.toList()));
    }

    /** The balances they left ("900 coins and 49 gems"). */
    public static String describeBalances(List<TeleportPayment> payments) {
        return describe(payments.stream().map(p -> amount(p.newBalance(), p.currency())).collect(Collectors.toList()));
    }
}
