package net.knightsandkings.knk.paper.commands;

import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import net.knightsandkings.knk.paper.currency.PlayerCurrencyService;

/**
 * {@code /balance [player]} ({@code /bal}, {@code /money}; currency DESIGN.md §3.6): your coins and
 * gems, or another player's with knk.balance.others - always read from the API, never the cache.
 */
public class BalanceCommand implements TabExecutor {

    private final PlayerCurrencyService currency;

    public BalanceCommand(PlayerCurrencyService currency) {
        this.currency = currency;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        currency.balance(sender, args.length > 0 ? args[0] : null);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? currency.visiblePlayers().names(sender, args[0]) : List.of();
    }
}
