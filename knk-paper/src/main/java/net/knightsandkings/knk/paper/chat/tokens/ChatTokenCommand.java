package net.knightsandkings.knk.paper.chat.tokens;

import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * {@code /chattoken <id> <public|plain|never>}: the buttons of the chat-token share prompt (PandiO/knk-workspace#233).
 * Not meant to be typed; open to every player (it only answers the player's own prompt).
 */
public final class ChatTokenCommand implements TabExecutor {

    private final ChatTokenService service;

    public ChatTokenCommand(ChatTokenService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players answer chat-token questions.");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage("Type #tokens in chat to see the chat tokens.");
            return true;
        }
        service.answer(player, args[0], args[1]);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
