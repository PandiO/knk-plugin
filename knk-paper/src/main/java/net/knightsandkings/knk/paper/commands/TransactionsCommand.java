package net.knightsandkings.knk.paper.commands;

import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import net.knightsandkings.knk.core.domain.users.BalanceCurrency;
import net.knightsandkings.knk.paper.currency.PlayerCurrencyService;

/**
 * {@code /transactions [player] [coins|gems|xp] [page]} ({@code /tx}; currency DESIGN.md §3.6,
 * KNG-23): your ledger history, newest first, or another player's with knk.transactions.others.
 * Arguments in any order: a number is the page, a currency word the filter, anything else the player.
 */
public class TransactionsCommand implements TabExecutor {

    private final PlayerCurrencyService currency;

    public TransactionsCommand(PlayerCurrencyService currency) {
        this.currency = currency;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String player = null;
        BalanceCurrency filter = null;
        int page = 1;
        for (String arg : args) {
            BalanceCurrency parsed = parseFilter(arg);
            if (parsed != null) {
                filter = parsed;
            } else if (!arg.isEmpty() && Character.isDigit(arg.charAt(0))) {
                page = BaltopCommand.parsePage(arg);
                if (page < 1) {
                    sender.sendMessage("§eUsage: /transactions [player] [coins|gems|xp] [page]");
                    return true;
                }
            } else if (player == null) {
                player = arg;
            } else {
                sender.sendMessage("§eUsage: /transactions [player] [coins|gems|xp] [page]");
                return true;
            }
        }
        currency.transactions(sender, player, filter, page);
        return true;
    }

    /** "coins"/"gems"/"xp" (also singular, "exp", "experience"). */
    static BalanceCurrency parseFilter(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "xp", "exp", "experience" -> BalanceCurrency.EXPERIENCE;
            default -> PayCommand.parseCurrency(value);
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) {
            return List.of();
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> filters = List.of("coins", "gems", "xp").stream().filter(c -> c.startsWith(last)).toList();
        if (args.length == 1) {
            return java.util.stream.Stream.concat(currency.visiblePlayers().names(sender, args[0]).stream(), filters.stream()).toList();
        }
        return filters;
    }
}
