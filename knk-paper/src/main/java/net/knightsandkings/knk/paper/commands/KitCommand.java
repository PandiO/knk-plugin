package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.dataaccess.KitsDataAccess;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.item.KnkKit;
import net.knightsandkings.knk.core.domain.item.KnkKitAvailability;
import net.knightsandkings.knk.paper.kit.KitGrantFlow;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /kit} - the complete grant surface (docs/specs/kits/DESIGN.md §4.3): list/get/give/
 * purchase, plus a pure FormWizard-pointer {@code manage} sub-tree (§4.5, revised at §0c - no
 * in-game CRUD). Plain {@link CommandExecutor} with manual subcommand dispatch, matching
 * {@code ItemCommand}/{@code GateCommand}/{@code ItemBlueprintsDebugCommand}'s existing
 * structure (this codebase doesn't use Aikar's ACF anywhere). Permission nodes are checked via
 * {@link KnkPermissible}, not a {@code plugin.yml} {@code permission:} entry - matching
 * {@code ModeCommand}'s precedent: Bukkit would check a plugin.yml node before this executor
 * ever ran, which would make per-subcommand gating (list/get/give/purchase/manage each their
 * own node) unreachable.
 * <p>
 * get/give/purchase go through {@link KitGrantFlow} - the same code path the
 * {@code kits.overview} menu uses (Kits DESIGN.md §7, CONTENT_PORT_PLAN.md CP2); this class only
 * parses arguments and resolves the kit by name.
 */
public class KitCommand implements CommandExecutor {

    private final Plugin plugin;
    private final KitsDataAccess kitsDataAccess;
    private final KitGrantFlow kitGrantFlow;

    public KitCommand(Plugin plugin, KitsDataAccess kitsDataAccess, KitGrantFlow kitGrantFlow) {
        this.plugin = plugin;
        this.kitsDataAccess = kitsDataAccess;
        this.kitGrantFlow = kitGrantFlow;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        switch (sub) {
            case "list" -> handleList(sender);
            case "get" -> handleGet(sender, rest);
            case "give" -> handleGive(sender, rest);
            case "purchase", "buy" -> handlePurchase(sender, rest);
            case "manage" -> handleManage(sender, rest);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage: /kit list | /kit get <name> | " +
                "/kit give <player> <name> | /kit purchase <name> | /kit manage <create|set|content|delete>");
    }

    // ===== /kit list =====

    private void handleList(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (!requirePermission(player, "knk.kit.list")) return;

        Integer userId = requireUserId(player);
        if (userId == null) return;

        sender.sendMessage(ChatColor.GRAY + "Fetching available kits...");

        kitsDataAccess.getAvailableForUserAsync(userId)
                .thenAccept(kits -> Bukkit.getScheduler().runTask(plugin, () -> printAvailability(sender, kits)))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> printError(sender, ex));
                    return null;
                });
    }

    private void printAvailability(CommandSender sender, List<KnkKitAvailability> kits) {
        if (kits == null || kits.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "No kits available.");
            return;
        }

        sender.sendMessage(ChatColor.GREEN + "Kits (" + kits.size() + "):");
        for (KnkKitAvailability kit : kits) {
            ChatColor statusColor = kit.canClaim() ? ChatColor.GREEN : ChatColor.RED;
            String status = kit.canClaim() ? "available" : safe(kit.denialReason(), "unavailable");

            StringBuilder line = new StringBuilder()
                    .append(ChatColor.WHITE).append(safe(kit.name(), "(unnamed)"))
                    .append(ChatColor.GRAY).append(" - ").append(statusColor).append(status);

            if (kit.costAmount() != null && kit.costAmount() > 0) {
                line.append(ChatColor.GRAY).append(" | cost: ").append(ChatColor.GOLD)
                        .append(kit.costAmount()).append(' ').append(safe(kit.costCurrency(), ""));
            }
            if (kit.isSinglePurchasePremium()) {
                line.append(ChatColor.GRAY).append(" | premium: ").append(ChatColor.AQUA)
                        .append(kit.isPurchased() ? "purchased" : (kit.premiumPriceGems() + " gems"));
            }

            sender.sendMessage(line.toString());
        }
    }

    // ===== /kit get <name> =====

    private void handleGet(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (!requirePermission(player, "knk.kit.get")) return;

        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /kit get <name>");
            return;
        }

        Integer userId = requireUserId(player);
        if (userId == null) return;

        String kitName = String.join(" ", args);
        sender.sendMessage(ChatColor.GRAY + "Claiming kit \"" + kitName + "\"...");

        resolveKitIdByName(kitName).whenComplete((kitId, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null) {
                KitGrantFlow.printError(sender, ex);
            } else if (kitId == null) {
                sender.sendMessage(ChatColor.RED + "No kit named \"" + kitName + "\" found.");
            } else {
                kitGrantFlow.claim(player, userId, kitId, kitName);
            }
        }));
    }

    // ===== /kit give <player> <name> =====

    private void handleGive(CommandSender sender, String[] args) {
        if (!(sender instanceof Player senderPlayer)) {
            // Console may still use /kit give - permission checks fall back to "not a player, deny".
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return;
        }
        if (!requirePermission(senderPlayer, "knk.kit.give")) return;

        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /kit give <player> <name>");
            return;
        }

        Player targetPlayer = Bukkit.getPlayerExact(args[0]);
        if (targetPlayer == null) {
            sender.sendMessage(ChatColor.RED + "Player not found or not online: " + args[0]);
            return;
        }

        Integer targetUserId = kitGrantFlow.resolveUserId(targetPlayer);
        if (targetUserId == null) {
            sender.sendMessage(ChatColor.RED + "That player's account isn't loaded yet - try again in a moment.");
            return;
        }

        String kitName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        sender.sendMessage(ChatColor.GRAY + "Giving kit \"" + kitName + "\" to " + targetPlayer.getName() + "...");

        resolveKitIdByName(kitName).whenComplete((kitId, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null) {
                KitGrantFlow.printError(sender, ex);
            } else if (kitId == null) {
                sender.sendMessage(ChatColor.RED + "No kit named \"" + kitName + "\" found.");
            } else {
                kitGrantFlow.give(senderPlayer, targetPlayer, targetUserId, kitId, kitName);
            }
        }));
    }

    // ===== /kit purchase <name> =====

    private void handlePurchase(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        if (!requirePermission(player, "knk.kit.purchase")) return;

        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /kit purchase <name>");
            return;
        }

        Integer userId = requireUserId(player);
        if (userId == null) return;

        String kitName = String.join(" ", args);
        sender.sendMessage(ChatColor.GRAY + "Purchasing kit \"" + kitName + "\"...");

        resolveKitIdByName(kitName).whenComplete((kitId, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null) {
                KitGrantFlow.printError(sender, ex);
            } else if (kitId == null) {
                sender.sendMessage(ChatColor.RED + "No kit named \"" + kitName + "\" found.");
            } else {
                kitGrantFlow.purchase(player, userId, kitId, kitName);
            }
        }));
    }

    // ===== /kit manage - FormWizard pointer only, no CRUD (DESIGN.md §0c/§4.5) =====

    private void handleManage(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return;
        }
        if (!requirePermission(player, "knk.kit.manage")) return;

        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        String message = switch (sub) {
            case "create" -> "Kits are created through the web app: the Kit form (route /forms/kit). In-game kit creation isn't available.";
            case "set", "content", "delete" -> "Kits are edited and deleted through the web app: the Kit form (route /forms/kit/edit/<id>). In-game kit management isn't available.";
            default -> "Kits are created and edited through the web app: /forms/kit to create, /forms/kit/edit/<id> to edit an existing one. In-game kit management isn't available.";
        };

        sender.sendMessage(ChatColor.YELLOW + message);
    }

    // ===== Helpers =====

    private CompletableFuture<Integer> resolveKitIdByName(String name) {
        PagedQuery query = new PagedQuery(1, 50, null, null, false, Map.of("Name", name));
        return kitsDataAccess.searchAsync(query).thenApply(page -> {
            if (page == null || page.items() == null) {
                return null;
            }
            for (KnkKit kit : page.items()) {
                if (kit.name() != null && kit.name().equalsIgnoreCase(name)) {
                    return kit.id();
                }
            }
            return null;
        });
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(ChatColor.RED + "Only players can use this command.");
        return null;
    }

    private boolean requirePermission(Player player, String node) {
        return kitGrantFlow.requirePermission(player, node);
    }

    private Integer requireUserId(Player player) {
        return kitGrantFlow.requireUserId(player);
    }

    private void printError(CommandSender sender, Throwable ex) {
        KitGrantFlow.printError(sender, ex);
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
