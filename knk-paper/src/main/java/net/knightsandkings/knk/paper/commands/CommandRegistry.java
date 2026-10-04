package net.knightsandkings.knk.paper.commands;

import org.bukkit.command.CommandSender;

import net.knightsandkings.knk.paper.commands.support.CommandPermissions;

import java.util.*;

/**
 * Registry for knk subcommands with metadata.
 * <p>
 * Each subcommand's metadata permission is checked through {@link CommandPermissions} (KNG-24): a
 * Bukkit grant or an in-house one (group grants, wildcards such as {@code knk.*} /
 * {@code knk.admin.*}). Plain {@code sender.hasPermission} refused every in-house grant.
 */
public class CommandRegistry {
    private final Map<String, RegisteredCommand> commands = new LinkedHashMap<>();
    private final Map<String, String> aliases = new HashMap<>();
    private CommandPermissions permissions = CommandPermissions.bukkitOnly();

    /** The permission check for {@link #execute} and {@link #listAvailable}; Bukkit-only until set. */
    public void setPermissions(CommandPermissions permissions) {
        this.permissions = Objects.requireNonNull(permissions, "permissions must not be null");
    }

    /**
     * Runs the subcommand if the sender holds its metadata permission (none = open); otherwise
     * tells them they lack it, or that it couldn't be checked. May run after this returns, on the
     * main thread, when the in-house check has to ask knk-web-api.
     */
    public void execute(CommandSender sender, RegisteredCommand cmd, String[] args) {
        permissions.whenAllowed(sender, cmd.metadata().permission(), () -> cmd.executor().execute(sender, args));
    }

    /** Every subcommand's metadata permission, for {@link CommandPermissions#warm}. */
    public Set<String> permissionNodes() {
        Set<String> nodes = new LinkedHashSet<>();
        commands.values().forEach(cmd -> {
            if (cmd.metadata().permission() != null) {
                nodes.add(cmd.metadata().permission());
            }
        });
        return nodes;
    }

    public record RegisteredCommand(CommandMetadata metadata, SubcommandExecutor executor) {}

    /**
     * Register a subcommand.
     */
    public void register(CommandMetadata metadata, SubcommandExecutor executor) {
        commands.put(metadata.name().toLowerCase(), new RegisteredCommand(metadata, executor));
    }

    /**
     * Register with aliases.
     */
    public void register(CommandMetadata metadata, SubcommandExecutor executor, String... aliasNames) {
        String primaryName = metadata.name().toLowerCase();
        commands.put(primaryName, new RegisteredCommand(metadata, executor));
        for (String alias : aliasNames) {
            aliases.put(alias.toLowerCase(), primaryName);
        }
    }

    /**
     * Get registered command by name or alias.
     */
    public Optional<RegisteredCommand> get(String nameOrAlias) {
        String key = nameOrAlias.toLowerCase();
        String primary = aliases.getOrDefault(key, key);
        return Optional.ofNullable(commands.get(primary));
    }

    /**
     * List all commands the sender has permission for (cache-only for in-house grants - see
     * {@link CommandPermissions#has}).
     */
    public List<RegisteredCommand> listAvailable(CommandSender sender) {
        return commands.values().stream()
                .filter(cmd -> permissions.has(sender, cmd.metadata().permission()))
                .toList();
    }

    /**
     * List all registered commands.
     */
    public List<RegisteredCommand> listAll() {
        return new ArrayList<>(commands.values());
    }
}
