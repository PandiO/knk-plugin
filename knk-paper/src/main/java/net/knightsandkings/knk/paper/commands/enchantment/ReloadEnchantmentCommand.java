package net.knightsandkings.knk.paper.commands.enchantment;

import org.bukkit.command.CommandSender;

public class ReloadEnchantmentCommand implements EnchantmentSubcommand {
    private final EnchantmentCommandHandler handler;

    public ReloadEnchantmentCommand(EnchantmentCommandHandler handler) {
        this.handler = handler;
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String permission() {
        return "customenchantments.command.reload";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        handler.configManager().reload();
        handler.runReloadHook();
        sender.sendMessage(handler.colorize(handler.message("messages.cmd-reload", "&aPlugin configuration was reloaded.")));
        return true;
    }
}
