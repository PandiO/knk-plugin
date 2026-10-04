package net.knightsandkings.knk.paper.commands;

import java.util.Arrays;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.List;
import java.util.Locale;

import net.knightsandkings.knk.core.ports.api.UserAccountApi;
import net.knightsandkings.knk.paper.KnKPlugin;
import net.knightsandkings.knk.paper.chat.ChatCaptureManager;
import net.knightsandkings.knk.paper.config.KnkConfig;
import net.knightsandkings.knk.paper.user.UserManager;
import net.knightsandkings.knk.paper.utils.CommandCooldownManager;

/**
 * Command dispatcher for /account commands.
 * Handles subcommands: status, create, link.
 */
public class AccountCommandRegistry implements TabExecutor {
    private final CommandRegistry registry = new CommandRegistry();
    private final KnkConfig config;

    public AccountCommandRegistry(
        KnKPlugin plugin,
        UserManager userManager,
        ChatCaptureManager chatCaptureManager,
        UserAccountApi userAccountApi,
        KnkConfig config,
        CommandCooldownManager cooldownManager
    ) {
        this.config = config;

        AccountCommand accountCommand = new AccountCommand(userManager, config);
        AccountLinkCommand accountLinkCommand = new AccountLinkCommand(
            plugin,
            userManager,
            chatCaptureManager,
            userAccountApi,
            config,
            cooldownManager
        );

        registry.register(
            new CommandMetadata("status", "View your account status", "/account", "knk.account.use"),
            (sender, args) -> accountCommand.onCommand(sender, null, "account", args),
            "view"
        );

        registry.register(
            new CommandMetadata("link", "Use a link code to link account", "/account link [code]", "knk.account.use"),
            (sender, args) -> accountLinkCommand.onCommand(sender, null, "account", args)
        );
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return registry.get("status")
                .map(cmd -> cmd.executor().execute(sender, new String[0]))
                .orElseGet(() -> {
                    sendPrefixed(sender, "&cNo account commands are available.");
                    return true;
                });
        }

        String subcommandName = args[0].toLowerCase();
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);

        var registered = registry.get(subcommandName);
        if (registered.isEmpty()) {
            sendPrefixed(sender, "&cUnknown command: " + subcommandName);
            sendPrefixed(sender, "&7Usage: &f/account [create|link|status]");
            return true;
        }

        CommandRegistry.RegisteredCommand cmd = registered.get();

        if (cmd.metadata().permission() != null && !sender.hasPermission(cmd.metadata().permission())) {
            sendPrefixed(sender, "&cYou don't have permission to use this command.");
            return true;
        }

        return cmd.executor().execute(sender, subArgs);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return registry.listAvailable(sender).stream()
                .map(entry -> entry.metadata().name())
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private void sendPrefixed(CommandSender sender, String message) {
        sender.sendMessage(colorize(config.messages().prefix() + message));
    }

    private String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }
}
