package net.knightsandkings.knk.paper.hub;

import java.util.List;
import java.util.Objects;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.paper.utils.ColorOptions;
import net.kyori.adventure.text.Component;

/**
 * {@code /hub return <token>}: the clickable return offer after an API outage (hub DESIGN §12). Players don't type it;
 * the offer's button runs it with the token of their current return record.
 */
public final class HubCommand implements TabExecutor {

    private final HubOutageService outage;
    private final HubOutageService.Access access;
    private final HubOutageService.Transfer transfer;

    public HubCommand(HubOutageService outage, HubOutageService.Access access, HubOutageService.Transfer transfer) {
        this.outage = Objects.requireNonNull(outage, "outage");
        this.access = Objects.requireNonNull(access, "access");
        this.transfer = Objects.requireNonNull(transfer, "transfer");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can return.");
            return true;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("return")) {
            player.sendMessage(Component.text("Use the [Return to where you were] button in the chat.").color(ColorOptions.message));
            return true;
        }
        long token;
        try {
            token = args.length > 1 ? Long.parseLong(args[1]) : -1L;
        } catch (NumberFormatException e) {
            token = -1L;
        }
        if (token < 0) {
            outage.offerReturn(player);
            return true;
        }
        outage.returnTo(player, token, access, transfer);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("return") : List.of();
    }
}
