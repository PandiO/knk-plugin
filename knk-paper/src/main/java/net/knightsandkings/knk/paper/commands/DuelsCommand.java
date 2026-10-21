package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.paper.duel.DuelMatch;
import net.knightsandkings.knk.paper.duel.DuelService;
import net.knightsandkings.knk.paper.duel.ui.DuelMenuPort;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /duels} (arena duels DESIGN §6.1, §9.2), node {@code knk.duel}: the live-matches menu, and the two forms
 * P6's announcement buttons run, {@code /duels view <token>} ([Details]) and {@code /duels spectate <arenaId>}
 * ([Spectate]). The menus are P6's ({@link DuelMenuPort}); without them the command lists the live duels in chat.
 */
public final class DuelsCommand implements TabExecutor {

    private final Supplier<DuelService> service;
    private final Supplier<DuelMenuPort> menus;

    public DuelsCommand(Supplier<DuelService> service, Supplier<DuelMenuPort> menus) {
        this.service = service;
        this.menus = menus;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can watch duels.");
            return true;
        }
        DuelService duels = service.get();
        if (duels == null) {
            player.sendMessage(ChatColor.RED + "Duels are turned off.");
            return true;
        }
        DuelMenuPort port = menus.get();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("view") && args.length > 1) {
            UUID token = parseToken(args[1]);
            if (token == null || duels.match(token).isEmpty()) {
                player.sendMessage(ChatColor.RED + "That duel is over.");
            } else if (port == null || !port.openMatch(player, token)) {
                describe(player, duels.match(token).get());
            }
            return true;
        }
        if (sub.equals("spectate") && args.length > 1) {
            try {
                int arenaId = Integer.parseInt(args[1]);
                if (port != null) port.spectate(player, arenaId);
                else player.sendMessage(ChatColor.RED + "Spectating isn't available right now; walk to the arena's stands.");
            } catch (NumberFormatException notANumber) {
                player.sendMessage(ChatColor.RED + "Usage: /duels spectate <arena id>");
            }
            return true;
        }
        if (port == null || !port.openMatches(player)) {
            List<DuelMatch> live = duels.matches();
            player.sendMessage(ChatColor.GOLD + "Live duels: " + live.size());
            live.forEach(m -> describe(player, m));
        }
        return true;
    }

    private static void describe(Player player, DuelMatch m) {
        player.sendMessage(ChatColor.YELLOW + " " + m.nameOf(DuelSide.CHALLENGER) + " vs " + m.nameOf(DuelSide.OPPONENT)
                + ChatColor.GRAY + " — " + m.arena().name() + ", " + m.phase().name().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    private static UUID parseToken(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
