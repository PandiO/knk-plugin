package net.knightsandkings.knk.paper.commands;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.regions.managed.RepairReport;
import net.knightsandkings.knk.paper.regions.access.DomainAccessFlagSync;
import net.knightsandkings.knk.paper.regions.managed.ManagedRegionsBootstrap;
import net.knightsandkings.knk.paper.tasks.TempRegionRetentionTask;

/**
 * {@code /knk regions repair}: runs the managed-region repair that also runs at startup, e.g. after regions were edited by
 * hand or a domain's rename could not reach the server. Never renames a region (the API's
 * {@code POST /api/Regions/finalize-temp-names} does that). Safe to repeat. Also re-syncs every domain's
 * AllowEntry/AllowExit flags from the API (KNG-56).
 *
 * <p>{@code /knk regions temp} (KNG-103) reports the leftover {@code tempregion_worldtask_*} regions with the same policy
 * as the daily 14-day cleanup ({@link TempRegionRetentionTask}), plus {@code domain_<id>} regions no domain uses (report
 * only). {@code /knk regions temp purge [hours]} deletes the temp regions no domain uses that are older than
 * {@code hours} (default {@value #DEFAULT_PURGE_HOURS}, at least 1, so a region step still in progress is safe).
 */
public class RegionsAdminCommand {

    public static final String PERMISSION = "knk.admin.regions";
    static final int DEFAULT_PURGE_HOURS = 24;
    private static final int LISTED = 10;

    private final ManagedRegionsBootstrap managedRegions;
    private final DomainAccessFlagSync accessSync;
    private final Plugin plugin;
    private final TempRegionRetentionTask tempRegions;

    public RegionsAdminCommand(ManagedRegionsBootstrap managedRegions, DomainAccessFlagSync accessSync, Plugin plugin) {
        this(managedRegions, accessSync, plugin, null);
    }

    /** @param tempRegions the temp-region cleanup behind {@code /knk regions temp}; null leaves that subcommand off */
    public RegionsAdminCommand(ManagedRegionsBootstrap managedRegions, DomainAccessFlagSync accessSync, Plugin plugin,
                               TempRegionRetentionTask tempRegions) {
        this.managedRegions = managedRegions;
        this.accessSync = accessSync;
        this.plugin = plugin;
        this.tempRegions = tempRegions;
    }

    public static CommandMetadata metadata() {
        return new CommandMetadata(
                "regions",
                "Repair the WorldGuard parent, priority and flags of every Knights and Kings region (same pass as at startup)",
                "/knk regions <repair|temp [purge [hours]]>",
                PERMISSION,
                List.of("/knk regions repair", "/knk regions temp", "/knk regions temp purge 24"));
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("repair", "temp").stream().filter(option -> option.startsWith(prefix)).toList();
        }
        if (args.length == 2 && "temp".equalsIgnoreCase(args[0]) && "purge".startsWith(args[1].toLowerCase(Locale.ROOT))) {
            return List.of("purge");
        }
        return List.of();
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (args.length > 0 && "temp".equalsIgnoreCase(args[0])) {
            return temp(sender, args);
        }
        if (args.length == 0 || !"repair".equalsIgnoreCase(args[0])) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk regions repair | /knk regions temp [purge [hours]]");
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
        if (accessSync != null) {
            accessSync.syncNow().whenComplete((report, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    sender.sendMessage(ChatColor.RED + "Domain access sync could not run: " + cause.getMessage()
                            + " (the rules last saved on the regions stay in force)");
                    return;
                }
                sender.sendMessage((report.failures().isEmpty() ? ChatColor.GREEN : ChatColor.YELLOW)
                        + "Domain access sync: " + report.summary());
                report.failures().stream().limit(5).forEach(failure -> sender.sendMessage(ChatColor.RED + failure));
            }));
        }
        return true;
    }

    private boolean temp(CommandSender sender, String[] args) {
        if (tempRegions == null) {
            sender.sendMessage(ChatColor.RED + "The temp-region cleanup is not running on this server.");
            return true;
        }
        boolean purge = args.length > 1 && "purge".equalsIgnoreCase(args[1]);
        if (args.length > 1 && !purge) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk regions temp [purge [hours]]");
            return true;
        }
        int hours = DEFAULT_PURGE_HOURS;
        if (purge && args.length > 2) {
            try {
                hours = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                hours = 0;
            }
            if (hours < 1) {
                sender.sendMessage(ChatColor.RED + "hours must be a whole number of at least 1");
                return true;
            }
        }
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(hours);
        int purgeHours = hours;
        sender.sendMessage(ChatColor.GRAY + (purge ? "Deleting unused temp regions older than " + hours + "h..."
                : "Checking temp regions (asks the API per region)..."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            TempRegionRetentionTask.Sweep sweep;
            try {
                sweep = tempRegions.sweep(cutoff, purge, !purge);
            } catch (RuntimeException e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(ChatColor.RED + "Temp-region check failed: " + e.getMessage()));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> report(sender, sweep, purge, purgeHours));
        });
        return true;
    }

    private static void report(CommandSender sender, TempRegionRetentionTask.Sweep sweep, boolean purge, int hours) {
        sender.sendMessage(ChatColor.GREEN + "Temp regions: " + sweep.temporaryTotal() + " found"
                + (purge ? ", " + sweep.deleted().size() + " deleted" : ", " + sweep.deletable().size()
                        + " unused and older than " + hours + "h")
                + ", " + sweep.inUse().size() + " used by a domain, " + sweep.tooRecent().size() + " too recent, "
                + sweep.noTimestamp().size() + " without a creation time (kept), " + sweep.usageUnknown().size()
                + " not checked (API)" + (sweep.deleteFailed().isEmpty() ? "" : ", " + sweep.deleteFailed().size()
                        + " failed to delete"));
        list(sender, purge ? "Deleted" : "Unused (purge deletes)", purge ? sweep.deleted() : sweep.deletable());
        list(sender, "Used by a domain (POST /api/Regions/finalize-temp-names renames them)", sweep.inUse());
        list(sender, "Delete failed", sweep.deleteFailed());
        if (!purge) {
            list(sender, "domain_<id> regions no domain uses (not deleted; check /rg info first)", sweep.orphanedFinalNames());
            if (!sweep.deletable().isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "Run /knk regions temp purge [hours] to delete the unused ones.");
            }
        }
    }

    private static void list(CommandSender sender, String title, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        sender.sendMessage(ChatColor.YELLOW + title + ": " + String.join(", ", names.stream().limit(LISTED).toList())
                + (names.size() > LISTED ? " (+" + (names.size() - LISTED) + " more)" : ""));
    }
}
