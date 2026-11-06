package net.knightsandkings.knk.paper.siege.survival;

import net.knightsandkings.knk.paper.commands.CommandMetadata;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /knk siege survival status [lobby]} (DESIGN §11 observability, link S5): the running survival matches with
 * round, queue/alive, stats, frontier, ghosts, the end-condition snapshot and the enemies. Read-only; nothing persisted.
 * The runtime is read lazily because {@code /knk} is registered before the siege runtime exists.
 */
public final class SurvivalAdminCommand {

    public static final String PERMISSION = "knk.admin.siege";

    private final Supplier<SurvivalMatchRuntime> runtime;

    public SurvivalAdminCommand(Supplier<SurvivalMatchRuntime> runtime) {
        this.runtime = runtime;
    }

    public static CommandMetadata metadata() {
        return new CommandMetadata(
            "siege",
            "Siege runtime diagnostics: /knk siege survival status [lobby] shows the running Siege Survival matches",
            "/knk siege survival status [lobby]",
            PERMISSION,
            List.of("/knk siege survival status", "/knk siege survival status cinix"));
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) return filter(List.of("survival"), args.length == 0 ? "" : args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("survival")) return filter(List.of("status"), args[1]);
        return List.of();
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[0].equalsIgnoreCase("survival") || !args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(ChatColor.RED + "Usage: /knk siege survival status [lobby]");
            return true;
        }
        SurvivalMatchRuntime rt = runtime.get();
        if (rt == null) {
            sender.sendMessage(ChatColor.RED + "The Siege Survival runtime is not running (siege disabled or not initialized).");
            return true;
        }
        String lobby = args.length > 2 ? args[2] : null;
        for (String line : rt.statusLines(lobby)) {
            sender.sendMessage(ChatColor.GRAY + line);
        }
        return true;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(p)).toList();
    }
}
