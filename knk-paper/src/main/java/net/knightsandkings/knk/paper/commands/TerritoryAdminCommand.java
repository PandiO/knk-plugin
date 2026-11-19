package net.knightsandkings.knk.paper.commands;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import net.knightsandkings.knk.core.territory.TerritoryCache;
import net.knightsandkings.knk.core.territory.TerritoryService;
import net.knightsandkings.knk.core.territory.TerritoryVerdict;

/**
 * {@code /knk territory} (PandiO/knk-workspace#689, node {@value #PERMISSION}): the territory lookup and cache for staff.
 * <ol>
 *   <li>{@code here}: the holder of the sender's block column (or "contested", "wilderness"), whether the chunk is split,
 *       and the cache version.</li>
 *   <li>{@code status}: chunks, split chunks and worlds of the cache, its version and build time, the API's flag, the
 *       last report and the cache's warnings.</li>
 *   <li>{@code rebuild}: reads the inputs and region shapes now and rebuilds the cache, then reports again.</li>
 * </ol>
 * With {@code production.enabled: false} every form answers that territory is off.
 */
public final class TerritoryAdminCommand {

    public static final String PERMISSION = "knk.admin.world.territory";
    private static final int LISTED_WARNINGS = 5;

    private final Supplier<TerritoryService> service;
    private final Plugin plugin;

    /** @param service the running territory service; null while {@code production.enabled} is off */
    public TerritoryAdminCommand(Supplier<TerritoryService> service, Plugin plugin) {
        this.service = service;
        this.plugin = plugin;
    }

    public static CommandMetadata metadata() {
        return new CommandMetadata(
                "territory",
                "Look up the territory where you stand, show the territory cache, or rebuild it (production.enabled)",
                "/knk territory <here|status|rebuild>",
                PERMISSION,
                List.of("/knk territory here", "/knk territory status", "/knk territory rebuild"));
    }

    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("here", "status", "rebuild").stream().filter(option -> option.startsWith(prefix)).toList();
        }
        return List.of();
    }

    public boolean execute(CommandSender sender, String[] args) {
        TerritoryService territory = service.get();
        if (territory == null) {
            sender.sendMessage(ChatColor.RED + "Territory is off on this server (production.enabled: false).");
            return true;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "here" -> here(sender, territory);
            case "status" -> status(sender, territory);
            case "rebuild" -> rebuild(sender, territory);
            default -> sender.sendMessage(ChatColor.YELLOW + "Usage: /knk territory <here|status|rebuild>");
        }
        return true;
    }

    private void here(CommandSender sender, TerritoryService territory) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only a player can look up where they stand.");
            return;
        }
        Location location = player.getLocation();
        String world = location.getWorld() == null ? null : location.getWorld().getName();
        TerritoryCache cache = territory.cache();
        TerritoryVerdict verdict = cache.lookup(world, location.getBlockX(), location.getBlockZ());
        boolean split = cache.isSplit(world, location.getBlockX() >> 4, location.getBlockZ() >> 4);
        sender.sendMessage(ChatColor.GRAY + "Territory at " + ChatColor.WHITE + location.getBlockX() + " "
                + location.getBlockZ() + ChatColor.GRAY + " (chunk " + (location.getBlockX() >> 4) + " "
                + (location.getBlockZ() >> 4) + (split ? ", split" : "") + "): " + colour(verdict)
                + cache.describe(verdict));
        if (verdict.owner() != null && verdict.contesting().size() > 1) {
            sender.sendMessage(ChatColor.GRAY + "Awarded by an admin; also reached by " + verdict.contesting().size()
                    + " holders.");
        }
        sender.sendMessage(ChatColor.GRAY + "Cache version " + ChatColor.WHITE + shortVersion(cache.version()));
    }

    private void status(CommandSender sender, TerritoryService territory) {
        TerritoryCache cache = territory.cache();
        sender.sendMessage(ChatColor.GRAY + "Territory cache: " + ChatColor.WHITE + cache.chunkCount() + ChatColor.GRAY
                + " chunks (" + ChatColor.WHITE + cache.splitCount() + ChatColor.GRAY + " split) in "
                + ChatColor.WHITE + cache.worlds().size() + ChatColor.GRAY + " world(s); version " + ChatColor.WHITE
                + shortVersion(cache.version()) + ChatColor.GRAY + ", built "
                + (cache == TerritoryCache.EMPTY ? "never" : cache.builtAt() + " in " + cache.buildMillis() + " ms"));
        sender.sendMessage(ChatColor.GRAY + "Sources: " + ChatColor.WHITE + cache.inputs().sources().size()
                + ChatColor.GRAY + ", awards: " + ChatColor.WHITE + cache.inputs().awards().size() + ChatColor.GRAY
                + ", API Production:Enabled: " + ChatColor.WHITE + cache.inputs().productionEnabled());
        sender.sendMessage(ChatColor.GRAY + "Last report: " + ChatColor.WHITE + territory.lastReport());
        cache.warnings().stream().limit(LISTED_WARNINGS)
                .forEach(warning -> sender.sendMessage(ChatColor.YELLOW + "- " + warning));
        if (cache.warnings().size() > LISTED_WARNINGS) {
            sender.sendMessage(ChatColor.GRAY + "... and " + (cache.warnings().size() - LISTED_WARNINGS)
                    + " more (server log)");
        }
    }

    private void rebuild(CommandSender sender, TerritoryService territory) {
        sender.sendMessage(ChatColor.GRAY + "Rebuilding the territory cache...");
        territory.refresh("/knk territory rebuild", true).whenComplete((outcome, error) -> {
            Runnable reply = () -> {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    sender.sendMessage(ChatColor.RED + "Territory rebuild failed: " + cause.getMessage());
                } else {
                    sender.sendMessage(ChatColor.GREEN + "Territory: " + outcome.summary());
                }
            };
            if (Bukkit.isPrimaryThread()) {
                reply.run();
            } else if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, reply);
            }
        });
    }

    private static ChatColor colour(TerritoryVerdict verdict) {
        return verdict.isContested() ? ChatColor.GOLD : verdict.isWilderness() ? ChatColor.DARK_GRAY : ChatColor.WHITE;
    }

    private static String shortVersion(String version) {
        return version == null || version.isEmpty() ? "-" : version.length() > 12 ? version.substring(0, 12) : version;
    }
}
