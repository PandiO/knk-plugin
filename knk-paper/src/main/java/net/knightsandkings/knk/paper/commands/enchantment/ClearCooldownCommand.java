package net.knightsandkings.knk.paper.commands.enchantment;

import net.knightsandkings.knk.core.ports.enchantment.CooldownManager;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ClearCooldownCommand implements EnchantmentSubcommand {
    private final EnchantmentCommandHandler handler;
    private final CooldownManager cooldownManager;
    private final EnchantmentCommandValidator validator;

    public ClearCooldownCommand(
            EnchantmentCommandHandler handler,
            CooldownManager cooldownManager,
            EnchantmentCommandValidator validator
    ) {
        this.handler = handler;
        this.cooldownManager = cooldownManager;
        this.validator = validator;
    }

    @Override
    public String name() {
        return "cooldown-clear";
    }

    @Override
    public String permission() {
        return "customenchantments.command.cooldown.clear";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player target;
        if (args.length == 0) {
            Optional<Player> playerOptional = validator.requirePlayer(sender);
            if (playerOptional.isEmpty()) {
                sender.sendMessage(handler.colorize(handler.message("messages.cmd-usage-cooldown", "&eUsage: /ce cooldown clear [player]")));
                return true;
            }
            target = playerOptional.get();
        } else {
            if (!sender.hasPermission("customenchantments.command.cooldown.clear.others")) {
                sender.sendMessage(handler.colorize(handler.message("messages.cmd-no-permission", "&cYou don't have permission to use this command.")));
                return true;
            }

            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(handler.colorize(handler.message("messages.cmd-player-not-found", "&cCould not find player %player%.", Map.of("%player%", args[0]))));
                return true;
            }
        }

        cooldownManager.clearCooldowns(target.getUniqueId()).join();
        sender.sendMessage(handler.colorize(handler.message(
                "messages.cmd-clear",
                "&aRemoved cooldowns for %player%.",
                Map.of("%player%", target.getName())
        )));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1 && sender.hasPermission("customenchantments.command.cooldown.clear.others")) {
            return handler.visiblePlayerNames(sender, args[0]);
        }
        return List.of();
    }
}
