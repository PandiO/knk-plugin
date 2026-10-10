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
    private final Map<String, java.util.function.Predicate<CommandSender>> visibility = new HashMap<>();
    /** Nodes a visibility predicate reads from the cache, asked for with {@link #permissionNodes()}. */
    private final Map<String, Collection<String>> visibilityNodes = new HashMap<>();

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

    /**
     * Every subcommand's metadata permission and the nodes its visibility predicate reads, for
     * {@link CommandPermissions#warm} before a listing.
     */
    public Set<String> permissionNodes() {
        Set<String> nodes = new LinkedHashSet<>();
        commands.values().forEach(cmd -> {
            if (cmd.metadata().permission() != null) {
                nodes.add(cmd.metadata().permission());
            }
        });
        visibilityNodes.values().forEach(nodes::addAll);
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
     * Extra condition for listing (help, tab completion) a subcommand registered without a top-level
     * permission because each of its actions checks its own node (e.g. /knk location): without it such
     * a command is listed to everyone. Running it is unaffected - the actions still check their nodes.
     */
    public void setVisibility(String name, java.util.function.Predicate<CommandSender> visibleTo) {
        visibility.put(name.toLowerCase(), Objects.requireNonNull(visibleTo, "visibleTo must not be null"));
    }

    /**
     * As {@link #setVisibility(String, java.util.function.Predicate)}, naming the nodes the predicate
     * reads from the cache so a listing can ask for them first ({@link #permissionNodes()}).
     */
    public void setVisibility(String name, java.util.function.Predicate<CommandSender> visibleTo, Collection<String> nodes) {
        setVisibility(name, visibleTo);
        visibilityNodes.put(name.toLowerCase(), List.copyOf(nodes));
    }

    /**
     * KNG-107: lists {@code name} only to senders holding at least one of {@code nodes} (cached check),
     * for a subcommand registered without a top-level node whose actions each check one of them.
     */
    public void setVisibleToAny(String name, Collection<String> nodes) {
        List<String> anyOf = List.copyOf(nodes);
        setVisibility(name, sender -> anyOf.stream().anyMatch(node -> permissions.has(sender, node)), anyOf);
    }

    /**
     * Whether help and tab completion offer {@code cmd} to the sender: its metadata permission
     * (cache-only for in-house grants - see {@link CommandPermissions#has}) and its visibility
     * predicate, if any. Running it doesn't depend on this.
     */
    public boolean isListed(CommandSender sender, RegisteredCommand cmd) {
        return permissions.has(sender, cmd.metadata().permission())
                && visibility.getOrDefault(cmd.metadata().name().toLowerCase(), s -> true).test(sender);
    }

    /** Whether any subcommand but {@code except} is listed to the sender (e.g. whether /knk help has anything to show). */
    public boolean anyListedExcept(CommandSender sender, String except) {
        return commands.values().stream()
                .filter(cmd -> !cmd.metadata().name().equalsIgnoreCase(except))
                .anyMatch(cmd -> isListed(sender, cmd));
    }

    /** Every subcommand listed to the sender ({@link #isListed}). */
    public List<RegisteredCommand> listAvailable(CommandSender sender) {
        return commands.values().stream()
                .filter(cmd -> isListed(sender, cmd))
                .toList();
    }

    /**
     * List all registered commands.
     */
    public List<RegisteredCommand> listAll() {
        return new ArrayList<>(commands.values());
    }
}
