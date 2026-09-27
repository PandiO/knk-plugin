package net.knightsandkings.knk.paper.commands;

import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import net.knightsandkings.knk.core.domain.users.BalanceCurrency;
import net.knightsandkings.knk.paper.currency.PlayerCurrencyService;

/** {@code /baltop [coins|gems] [page]} (currency DESIGN.md §3.6): the richest players, from the API's leaderboard. */
public class BaltopCommand implements TabExecutor {

    private final PlayerCurrencyService currency;

    public BaltopCommand(PlayerCurrencyService currency) {
        this.currency = currency;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        BalanceCurrency which = BalanceCurrency.COINS;
        int page = 1;
        for (String arg : args) {
            BalanceCurrency parsed = PayCommand.parseCurrency(arg);
            if (parsed != null) {
                which = parsed;
            } else {
                page = parsePage(arg);
                if (page < 1) {
                    sender.sendMessage("§eUsage: /baltop [coins|gems] [page]");
                    return true;
                }
            }
        }
        currency.baltop(sender, which, page);
        return true;
    }

    /** A page number ≥ 1, or -1. */
    static int parsePage(String value) {
        try {
            int page = Integer.parseInt(value);
            return page >= 1 && page <= 10_000 ? page : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("coins", "gems").stream().filter(c -> c.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
