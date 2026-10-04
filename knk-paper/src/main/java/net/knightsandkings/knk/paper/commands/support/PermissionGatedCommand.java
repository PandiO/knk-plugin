package net.knightsandkings.knk.paper.commands.support;

import java.util.List;
import java.util.Objects;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.TabExecutor;

/**
 * Gates a whole command on one node through {@link CommandPermissions} (KNG-24) - instead of a
 * {@code permission:} entry in plugin.yml, which Bukkit checks against its own permission tree
 * only, so in-house grants never passed and Paper hid the command ("Unknown or incomplete
 * command"). The delegate runs only for senders holding the node; the others are told they lack
 * permission and get no completions.
 */
public final class PermissionGatedCommand implements TabExecutor {

    private final String node;
    private final CommandExecutor delegate;
    private final CommandPermissions permissions;

    public PermissionGatedCommand(String node, CommandExecutor delegate, CommandPermissions permissions) {
        this.node = Objects.requireNonNull(node, "node must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.permissions = Objects.requireNonNull(permissions, "permissions must not be null");
    }

    public String node() {
        return node;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        permissions.whenAllowed(sender, node, () -> delegate.onCommand(sender, command, label, args));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!permissions.has(sender, node)) {
            return List.of();
        }
        // Suppress Bukkit's default all-online-player fallback when the delegate has no completer.
        return delegate instanceof TabCompleter completer ? completer.onTabComplete(sender, command, alias, args) : List.of();
    }
}
