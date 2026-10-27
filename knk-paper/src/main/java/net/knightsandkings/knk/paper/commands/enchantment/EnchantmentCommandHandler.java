package net.knightsandkings.knk.paper.commands.enchantment;

import net.knightsandkings.knk.core.ports.enchantment.CooldownManager;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import net.knightsandkings.knk.paper.config.EnchantmentConfigManager;
import net.knightsandkings.knk.paper.enchantment.EnchantmentPermissions;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;
import net.knightsandkings.knk.paper.commands.support.VisiblePlayers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class EnchantmentCommandHandler implements CommandExecutor, TabCompleter {
    private final Plugin plugin;
    private final EnchantmentConfigManager configManager;
    private final EnchantmentCommandValidator validator;
    private final EnchantmentPermissions permissions;
    private final Map<String, EnchantmentSubcommand> subcommands;
    private final VisiblePlayers visiblePlayers = VisiblePlayers.bukkit();
    // PandiO/knk-workspace#151: /ce reload also reloads the enchantments' combat values from the API.
    private volatile Runnable reloadHook = () -> { };

    public EnchantmentCommandHandler(
            Plugin plugin,
            EnchantmentConfigManager configManager,
            EnchantmentRepository repository,
            CooldownManager cooldownManager
    ) {
        this(plugin, configManager, repository, cooldownManager, EnchantmentPermissions.bukkitOnly());
    }

    /**
     * @param permissions the subcommand nodes and {@code /ce add <id>} are checked through Bukkit and the
     *                    rank model (PandiO/knk-workspace#193)
     */
    public EnchantmentCommandHandler(
            Plugin plugin,
            EnchantmentConfigManager configManager,
            EnchantmentRepository repository,
            CooldownManager cooldownManager,
            EnchantmentPermissions permissions
    ) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.permissions = permissions != null ? permissions : EnchantmentPermissions.bukkitOnly();
        this.validator = new EnchantmentCommandValidator(this.permissions);
        this.subcommands = new LinkedHashMap<>();

        register(new AddEnchantmentCommand(this, repository, validator));
        register(new RemoveEnchantmentCommand(this, repository, validator));
        register(new InfoEnchantmentCommand(this, repository, cooldownManager, validator));
        register(new ClearCooldownCommand(this, cooldownManager, validator));
        register(new ReloadEnchantmentCommand(this));
    }

    /** Runs on /ce reload after the config reload (the combat-values refresh). */
    public void setReloadHook(Runnable reloadHook) {
        this.reloadHook = reloadHook != null ? reloadHook : () -> { };
    }

    void runReloadHook() {
        reloadHook.run();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        EnchantmentSubcommand subcommand;
        String[] subArgs;

        if ("cooldown".equals(first)) {
            if (args.length < 2) {
                sender.sendMessage(colorize(message("messages.cmd-usage-cooldown", "&eUsage: /ce cooldown clear [player]")));
                return true;
            }
            String nested = args[1].toLowerCase(Locale.ROOT);
            subcommand = "clear".equals(nested) ? subcommands.get("cooldown-clear") : null;
            subArgs = copyArgs(args, 2);
        } else {
            subcommand = subcommands.get(first);
            subArgs = copyArgs(args, 1);
        }

        if (subcommand == null) {
            sender.sendMessage(colorize(message("messages.cmd-unknown", "&cUnknown subcommand.")));
            sendHelp(sender);
            return true;
        }

        String permission = subcommand.permission();
        EnchantmentSubcommand chosen = subcommand;
        permissions.commands().whenAllowed(sender, permission == null || permission.isBlank() ? null : permission,
                () -> chosen.execute(sender, subArgs));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length <= 1) {
            List<String> root = List.of("add", "remove", "info", "cooldown", "reload").stream()
                    .filter(name -> mayUse(sender, subcommands.get("cooldown".equals(name) ? "cooldown-clear" : name)))
                    .toList();
            return filterByPrefix(root, args.length == 0 ? "" : args[0]);
        }

        String first = args[0].toLowerCase(Locale.ROOT);
        if ("cooldown".equals(first)) {
            if (args.length == 2) {
                return filterByPrefix(List.of("clear"), args[1]);
            }
            if (args.length >= 3 && "clear".equalsIgnoreCase(args[1])) {
                EnchantmentSubcommand subcommand = subcommands.get("cooldown-clear");
                if (subcommand != null) {
                    return subcommand.tabComplete(sender, copyArgs(args, 2));
                }
            }
            return List.of();
        }

        EnchantmentSubcommand subcommand = subcommands.get(first);
        if (subcommand == null || !mayUse(sender, subcommand)) {
            return List.of();
        }
        return subcommand.tabComplete(sender, copyArgs(args, 1));
    }

    /** Whether tab completion offers the subcommand (Bukkit or a cached rank grant of its node). */
    private boolean mayUse(CommandSender sender, EnchantmentSubcommand subcommand) {
        if (subcommand == null) {
            return false;
        }
        String permission = subcommand.permission();
        return permission == null || permission.isBlank() || permissions.commands().hasForCompletion(sender, permission);
    }

    /** Who may use what: the per-enchantment nodes and the {@code /ce} subcommand nodes. */
    public EnchantmentPermissions permissions() {
        return permissions;
    }

    public Plugin plugin() {
        return plugin;
    }

    public EnchantmentConfigManager configManager() {
        return configManager;
    }

    public String message(String key, String fallback) {
        return configManager.getMessage(key, fallback);
    }

    public String message(String key, String fallback, Map<String, String> placeholders) {
        return configManager.getMessage(key, fallback, placeholders);
    }

    public String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public List<String> enchantmentIds() {
        return net.knightsandkings.knk.core.domain.enchantment.EnchantmentRegistry
                .getInstance()
                .getAll()
                .stream()
                .map(net.knightsandkings.knk.core.domain.enchantment.Enchantment::id)
                .sorted()
                .toList();
    }

    public List<String> visiblePlayerNames(CommandSender sender, String prefix) {
        return visiblePlayers.complete(sender, prefix);
    }

    private void register(EnchantmentSubcommand subcommand) {
        subcommands.put(subcommand.name().toLowerCase(Locale.ROOT), subcommand);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(colorize(message("messages.cmd-help-header", "&6Custom Enchantments Commands:")));
        sender.sendMessage(colorize("&e/ce add <enchantment> <level>"));
        sender.sendMessage(colorize("&e/ce remove <enchantment>"));
        sender.sendMessage(colorize("&e/ce info [player]"));
        sender.sendMessage(colorize("&e/ce cooldown clear [player]"));
        sender.sendMessage(colorize("&e/ce reload"));
        sender.sendMessage(colorize("&7For canonical definition debugging, use &f/knk enchantments ..."));
    }

    private List<String> filterByPrefix(List<String> source, String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return source.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized))
                .toList();
    }

    private String[] copyArgs(String[] args, int startIndex) {
        int size = Math.max(0, args.length - startIndex);
        String[] result = new String[size];
        if (size > 0) {
            System.arraycopy(args, startIndex, result, 0, size);
        }
        return result;
    }
}
