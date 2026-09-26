package net.knightsandkings.knk.paper.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.currency.AmountParser;
import net.knightsandkings.knk.core.domain.users.BalanceCurrency;
import net.knightsandkings.knk.paper.currency.PlayerCurrencyService;

/**
 * {@code /pay <player> <amount> [coins|gems]} and {@code /pay confirm|cancel [id]} (currency
 * DESIGN.md §3.6, D4: Essentials-style order; v1's {@code /pay <coins|gems> <name> <amount>} is
 * not accepted). Parsing only - {@link PlayerCurrencyService} does the rest. Gated on knk.pay
 * (and knk.pay.gems for gems) through KnkPermissible inside the service, so no plugin.yml
 * {@code permission:} entry.
 * <p>
 * A player literally named "confirm" or "cancel" can still be paid: {@code /pay confirm 100}
 * is a payment because 100 is an amount, while a pending id never is.
 */
public class PayCommand implements TabExecutor {

    private final PlayerCurrencyService currency;

    public PayCommand(PlayerCurrencyService currency) {
        this.currency = currency;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can pay.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(currency.settings().template("pay-usage"));
            return true;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        boolean control = (first.equals("confirm") || first.equals("cancel"))
            && args.length <= 2 && (args.length == 1 || AmountParser.parse(args[1]).isEmpty());
        if (control) {
            String id = args.length == 2 ? args[1] : null;
            if (first.equals("confirm")) {
                currency.confirm(player, id);
            } else {
                currency.cancel(player, id);
            }
            return true;
        }
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(currency.settings().template("pay-usage"));
            return true;
        }
        BalanceCurrency which = args.length == 3 ? parseCurrency(args[2]) : BalanceCurrency.COINS;
        if (which == null) {
            sender.sendMessage(currency.settings().template("pay-usage"));
            return true;
        }
        currency.pay(player, args[0], args[1], which);
        return true;
    }

    /** "coins"/"coin"/"gems"/"gem" (any case); XP is never payable. */
    static BalanceCurrency parseCurrency(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "coins", "coin" -> BalanceCurrency.COINS;
            case "gems", "gem" -> BalanceCurrency.GEMS;
            default -> null;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(currency.visiblePlayers().names(sender, args[0]));
            for (String word : List.of("confirm", "cancel")) {
                if (word.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    options.add(word);
                }
            }
            return options;
        }
        if (args.length == 3) {
            return List.of("coins", "gems").stream().filter(c -> c.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
