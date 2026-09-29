package net.knightsandkings.knk.paper.commands;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.regions.managed.RepairReport;
import net.knightsandkings.knk.paper.regions.managed.ManagedRegionsBootstrap;

/**
 * {@code /knk regions repair}: runs the managed-region repair that also runs at startup, e.g. after a Town or Structure was
 * created in the web app (their regions are set up by the next repair, not at creation). Safe to repeat.
 */
public class RegionsAdminCommand {

    public static final String PERMISSION = "knk.admin.regions";

    private final ManagedRegionsBootstrap managedRegions;
    private final Plugin plugin;

    public RegionsAdminCommand(ManagedRegionsBootstrap managedRegions, Plugin plugin) {
        this.managedRegions = managedRegions;
        this.plugin = plugin;
    }

    public static CommandMetadata metadata() {
        return new CommandMetadata(
                "regions",
                "Repair the WorldGuard parent, priority and flags of every Knights and Kings region (same pass as at startup)",
                "/knk regions repair",
                PERMISSION,
                List.of("/knk regions repair"));
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || !"repair".equalsIgnoreCase(args[0])) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk regions repair");
            return true;
        }
        sender.sendMessage(ChatColor.GRAY + "Repairing managed regions...");
        managedRegions.repairNow().whenComplete((report, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                sender.sendMessage(ChatColor.RED + "Region repair could not run: " + cause.getMessage());
                return;
            }
            sender.sendMessage((report.failed() > 0 || report.persistFailed() ? ChatColor.YELLOW : ChatColor.GREEN)
                    + "Region repair: " + report.summary());
            report.warnings().stream().limit(5).forEach(warning -> sender.sendMessage(ChatColor.GRAY + "- " + warning));
            if (report.failed() > 0) {
                report.entries().stream().filter(entry -> entry.outcome() == RepairReport.Outcome.FAILED).limit(5)
                        .forEach(entry -> sender.sendMessage(ChatColor.RED + entry.regionId() + ": " + entry.detail()));
            }
        }));
        return true;
    }
}
