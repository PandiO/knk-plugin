package net.knightsandkings.knk.paper.commands.support;

import java.util.List;
import java.util.Objects;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.TabExecutor;

/**
 * Gates a whole command on its node(s) through {@link CommandPermissions} (KNG-24) - instead of a
 * {@code permission:} entry in plugin.yml, which Bukkit checks against its own permission tree
 * only, so in-house grants never passed and Paper hid the command ("Unknown or incomplete
 * command"). The delegate runs only for senders holding one of the nodes (KNG-107: a command whose
 * forms need different nodes, see {@link CommandNodes}); the others are told they lack permission
 * - not the usage - and get no completions.
 */
public final class PermissionGatedCommand implements TabExecutor {

    private final List<String> nodes;
    private final CommandExecutor delegate;
    private final CommandPermissions permissions;

    public PermissionGatedCommand(String node, CommandExecutor delegate, CommandPermissions permissions) {
        this(List.of(Objects.requireNonNull(node, "node must not be null")), delegate, permissions);
    }

    public PermissionGatedCommand(List<String> nodes, CommandExecutor delegate, CommandPermissions permissions) {
        this.nodes = List.copyOf(nodes);
        if (this.nodes.isEmpty()) {
            throw new IllegalArgumentException("nodes must not be empty");
        }
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.permissions = Objects.requireNonNull(permissions, "permissions must not be null");
    }

    /** The first node (the only one for a single-node gate). */
    public String node() {
        return nodes.get(0);
    }

    public List<String> nodes() {
        return nodes;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        permissions.whenAnyAllowed(sender, nodes, () -> delegate.onCommand(sender, command, label, args));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!permissions.hasAnyForCompletion(sender, nodes)) {
            return List.of();
        }
        // Suppress Bukkit's default all-online-player fallback when the delegate has no completer.
        return delegate instanceof TabCompleter completer ? completer.onTabComplete(sender, command, alias, args) : List.of();
    }
}
