package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.paper.commands.support.VisiblePlayers;
import net.knightsandkings.knk.paper.duel.ui.DuelNegotiationUi;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /duel} (arena duels DESIGN §6.1), node {@code knk.duel} (default true, through {@code CommandNodes}):
 * <pre>
 * /duel &lt;player&gt; [quick]          open the proposal editor (or invite at once: no stakes, default gear mode)
 * /duel accept|counter|decline|view &lt;id&gt;
 * /duel cancel [id]                withdraw your invite / end that negotiation
 * /duel kit [kit]                  KIT_CHOICE in the warmup
 * /duel draw | toggle | claim
 * </pre>
 * The subcommand names win over player names. The ids are the offer ids the chat buttons carry.
 */
public final class DuelCommand implements TabExecutor {

    public static final String NODE = "knk.duel";
    private static final List<String> SUBCOMMANDS = List.of("accept", "counter", "decline", "view", "cancel", "kit",
            "draw", "toggle", "claim", "help");

    private final Supplier<DuelNegotiationUi> ui;
    private final VisiblePlayers players;

    public DuelCommand(Supplier<DuelNegotiationUi> ui, VisiblePlayers players) {
        this.ui = ui;
        this.players = players;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can duel.");
            return true;
        }
        DuelNegotiationUi duels = ui.get();
        if (duels == null) {
            player.sendMessage(ChatColor.RED + "Duels are turned off.");
            return true;
        }
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
            help(player);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "accept", "counter", "decline", "view" -> {
                Long id = args.length > 1 ? parseId(args[1]) : null;
                if (id == null && !"view".equals(sub)) {
                    player.sendMessage(ChatColor.RED + "Usage: /duel " + sub + " <offer id> (click the button in chat)");
                    return true;
                }
                switch (sub) {
                    case "accept" -> duels.accept(player, id);
                    case "counter" -> duels.counter(player, id);
                    case "decline" -> duels.decline(player, id);
                    default -> duels.view(player, id);
                }
            }
            case "cancel" -> duels.cancel(player, args.length > 1 ? parseId(args[1]) : null);
            case "kit" -> duels.kit(player, args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : null);
            case "draw" -> duels.draw(player);
            case "toggle" -> duels.toggle(player);
            case "claim" -> duels.claim(player);
            default -> {
                boolean quick = args.length > 1 && "quick".equalsIgnoreCase(args[1]);
                duels.invite(player, players.find(player, args[0]), args[0], quick);
            }
        }
        return true;
    }

    private static void help(Player player) {
        player.sendMessage(ChatColor.GOLD + "Duels (stand inside an arena to challenge someone):");
        player.sendMessage(ChatColor.YELLOW + "/duel <player>" + ChatColor.GRAY + " - propose a duel with stakes");
        player.sendMessage(ChatColor.YELLOW + "/duel <player> quick" + ChatColor.GRAY + " - a duel without stakes");
        player.sendMessage(ChatColor.YELLOW + "/duel cancel" + ChatColor.GRAY + " - withdraw your offer");
        player.sendMessage(ChatColor.YELLOW + "/duel kit" + ChatColor.GRAY + " - pick your kit in the warmup (kit-choice duels)");
        player.sendMessage(ChatColor.YELLOW + "/duel draw" + ChatColor.GRAY + " - offer or accept a draw during the fight");
        player.sendMessage(ChatColor.YELLOW + "/duel toggle" + ChatColor.GRAY + " - refuse or allow duel invites");
        player.sendMessage(ChatColor.YELLOW + "/duel claim" + ChatColor.GRAY + " - collect duel winnings or returned items");
        player.sendMessage(ChatColor.GRAY + "Answer offers with the buttons in chat, or /duel accept|counter|decline|view <id>.");
    }

    private static Long parseId(String raw) {
        try {
            return Long.parseLong(raw.startsWith("#") ? raw.substring(1) : raw);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>(SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList());
            out.addAll(players.completeOthers(sender, args[0]));
            return out;
        }
        if (args.length == 2 && !SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT))) {
            return "quick".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("quick") : List.of();
        }
        return List.of();
    }
}
