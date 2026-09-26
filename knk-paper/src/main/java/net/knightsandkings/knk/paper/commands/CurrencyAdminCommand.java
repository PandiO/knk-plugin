package net.knightsandkings.knk.paper.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.CommandSender;

import net.knightsandkings.knk.core.domain.users.BalanceCurrency;
import net.knightsandkings.knk.paper.currency.PlayerCurrencyService;

/**
 * {@code /knk currency reverse <txId> [--partial] <reason...> | history <player> [coins|gems|xp] [page]
 * | lock <player> <reason...> | unlock <player>} (currency DESIGN.md §3.6, IMPLEMENTATION_PLAN.md
 * Phase 4). Each action is gated on its own knk.admin.currency.* node through KnkPermissible inside
 * {@link PlayerCurrencyService} (the same strings the API checks for web callers), so the /knk
 * metadata carries no permission. Transaction ids come from {@code history}, where each line's id
 * suggests the reverse command when clicked. This class only parses arguments.
 */
public class CurrencyAdminCommand {
    static final List<String> ACTIONS = List.of("reverse", "history", "lock", "unlock");
    private static final String PARTIAL_FLAG = "--partial";

    private final PlayerCurrencyService currencyService;

    public CurrencyAdminCommand(PlayerCurrencyService currencyService) {
        this.currencyService = currencyService;
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !ACTIONS.contains(args[0].toLowerCase(Locale.ROOT))) {
            usage(sender);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "reverse" -> {
                if (args.length < 3) {
                    usage(sender);
                    return true;
                }
                List<String> words = new ArrayList<>(Arrays.asList(args).subList(2, args.length));
                boolean partial = words.removeIf(PARTIAL_FLAG::equalsIgnoreCase);
                currencyService.staffReverse(sender, args[1], String.join(" ", words), partial);
            }
            case "history" -> {
                if (args.length < 2) {
                    usage(sender);
                    return true;
                }
                BalanceCurrency currency = null;
                int page = 1;
                for (int i = 2; i < args.length; i++) {
                    BalanceCurrency parsed = parseCurrency(args[i]);
                    if (parsed != null) {
                        currency = parsed;
                        continue;
                    }
                    try {
                        page = Math.max(1, Integer.parseInt(args[i]));
                    } catch (NumberFormatException ex) {
                        usage(sender);
                        return true;
                    }
                }
                currencyService.staffHistory(sender, args[1], currency, page);
            }
            case "lock" -> {
                if (args.length < 3) {
                    usage(sender);
                    return true;
                }
                currencyService.staffLock(sender, args[1], String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
            }
            default -> {
                if (args.length != 2) {
                    usage(sender);
                    return true;
                }
                currencyService.staffUnlock(sender, args[1]);
            }
        }
        return true;
    }

    /** Suggestions after {@code /knk currency}: the action, then a visible player's name where one goes. */
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return ACTIONS.stream().filter(a -> a.startsWith(prefix)).toList();
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && !"reverse".equals(action)) {
            return currencyService.visiblePlayers().names(sender, args[1]);
        }
        if (args.length == 3 && "history".equals(action)) {
            return List.of("coins", "gems", "xp").stream().filter(c -> c.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && "reverse".equals(action)) {
            return PARTIAL_FLAG.startsWith(args[2].toLowerCase(Locale.ROOT)) ? List.of(PARTIAL_FLAG) : List.of();
        }
        return List.of();
    }

    static BalanceCurrency parseCurrency(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "coins", "coin" -> BalanceCurrency.COINS;
            case "gems", "gem" -> BalanceCurrency.GEMS;
            case "xp", "exp", "experience" -> BalanceCurrency.EXPERIENCE;
            default -> null;
        };
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(currencyService.settings().message("currency-admin-usage"));
    }
}
