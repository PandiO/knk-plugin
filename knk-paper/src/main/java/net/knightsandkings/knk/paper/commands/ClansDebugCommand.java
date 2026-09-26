package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.domain.clan.KnkBannerLayer;
import net.knightsandkings.knk.core.domain.clan.KnkClan;
import net.knightsandkings.knk.core.ports.api.ClansQueryApi;
import net.knightsandkings.knk.paper.clan.BannerDesignBukkitMapper;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * /knk clans - Siege Phase 1 verification command (docs/specs/siege-minigame/IMPLEMENTATION_PLAN.md
 * Phase 1 exit): fetch clans/banners from the API and hand the built banner item to the player.
 * <pre>
 *   /knk clans list
 *   /knk clans info &lt;clanId&gt;
 *   /knk clans banner &lt;clanId&gt;         - the clan's banner, named in its chat colour
 *   /knk clans design &lt;bannerDesignId&gt; - a banner design on its own
 * </pre>
 * API calls run async; replies and inventory changes are scheduled back on the main thread.
 */
public class ClansDebugCommand {
    private static final String USAGE = "Usage: /knk clans <list|info <clanId>|banner <clanId>|design <bannerDesignId>>";

    private final Plugin plugin;
    private final ClansQueryApi clansApi;

    public ClansDebugCommand(Plugin plugin, ClansQueryApi clansApi) {
        this.plugin = plugin;
        this.clansApi = clansApi;
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (clansApi == null) {
            sender.sendMessage(ChatColor.RED + "Clans API is not available.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + USAGE);
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("list")) {
            onMain(clansApi.listClans(), sender, clans -> sendList(sender, clans));
            return true;
        }
        if (args.length < 2 || !List.of("info", "banner", "design").contains(sub)) {
            sender.sendMessage(ChatColor.YELLOW + USAGE);
            return true;
        }
        int id;
        try {
            id = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "'" + args[1] + "' is not a number.");
            return true;
        }

        switch (sub) {
            case "info" -> onMain(clansApi.getClanById(id), sender, clan -> {
                if (clan == null) sender.sendMessage(ChatColor.RED + "No clan with id " + id + ".");
                else sendInfo(sender, clan);
            });
            case "banner" -> {
                Player player = requirePlayer(sender);
                if (player == null) return true;
                onMain(clansApi.getClanById(id), sender, clan -> {
                    if (clan == null) sender.sendMessage(ChatColor.RED + "No clan with id " + id + ".");
                    else give(player, BannerDesignBukkitMapper.toItemStack(clan, warner(player)), "banner of " + clan.name());
                });
            }
            default -> {
                Player player = requirePlayer(sender);
                if (player == null) return true;
                onMain(clansApi.getBannerDesignById(id), sender, design -> {
                    if (design == null) sender.sendMessage(ChatColor.RED + "No banner design with id " + id + ".");
                    else give(player, BannerDesignBukkitMapper.toItemStack(design, warner(player)), "banner design '" + design.name() + "'");
                });
            }
        }
        return true;
    }

    private void sendList(CommandSender sender, List<KnkClan> clans) {
        if (clans.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No clans yet.");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Clans (" + clans.size() + "):");
        for (KnkClan clan : clans) {
            sender.sendMessage(ChatColor.GRAY + "  #" + clan.id() + " " + ChatColor.WHITE + clan.name()
                    + (clan.npc() ? ChatColor.DARK_GRAY + " [NPC]" : "")
                    + (clan.defaultForTownName() != null ? ChatColor.GRAY + " - default for " + clan.defaultForTownName() : ""));
        }
    }

    private void sendInfo(CommandSender sender, KnkClan clan) {
        sender.sendMessage(ChatColor.GOLD + "Clan #" + clan.id() + ": " + ChatColor.WHITE + clan.name());
        sender.sendMessage(ChatColor.GRAY + "  NPC: " + clan.npc() + ", chat colour: " + clan.chatColor()
                + ", default for town: " + (clan.defaultForTownName() != null ? clan.defaultForTownName() : "-"));
        if (clan.bannerDesign() == null) {
            sender.sendMessage(ChatColor.GRAY + "  Banner #" + clan.bannerDesignId() + " (not included)");
            return;
        }
        sender.sendMessage(ChatColor.GRAY + "  Banner #" + clan.bannerDesign().id() + " '" + clan.bannerDesign().name()
                + "', base " + clan.bannerDesign().baseColor() + ", " + clan.bannerDesign().layers().size() + " layer(s):");
        for (KnkBannerLayer layer : clan.bannerDesign().layers()) {
            sender.sendMessage(ChatColor.DARK_GRAY + "    " + layer.sortOrder() + ": " + layer.patternKey() + " " + layer.color());
        }
    }

    private static Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) return player;
        sender.sendMessage(ChatColor.RED + "Only players can receive a banner.");
        return null;
    }

    private void give(Player player, ItemStack item, String what) {
        player.getInventory().addItem(item).values()
                .forEach(overflow -> player.getWorld().dropItemNaturally(player.getLocation(), overflow));
        player.sendMessage(ChatColor.GREEN + "Gave you the " + what + ".");
    }

    private Consumer<String> warner(Player player) {
        return message -> {
            plugin.getLogger().warning(message);
            player.sendMessage(ChatColor.YELLOW + message);
        };
    }

    private <T> void onMain(CompletableFuture<T> future, CommandSender sender, Consumer<T> onSuccess) {
        future.whenComplete((result, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                plugin.getLogger().warning("/knk clans failed: " + cause.getMessage());
                sender.sendMessage(ChatColor.RED + "API call failed: " + cause.getMessage());
            } else {
                onSuccess.accept(result);
            }
        }));
    }
}
