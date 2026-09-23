package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.paper.utils.DisplayTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Command handler for /knk item - held-item editing (rename/lore) plus the enchantment
 * definition catalog (merged in from the former standalone /knk enchantments command, per
 * developer request 2026-09-23).
 *
 * Supports:
 * - /knk item rename <displayName>
 * - /knk item lore add <lore>
 * - /knk item lore set <loreLine> <lore>
 * - /knk item lore remove <loreLine>
 * - /knk item enchantments ... (delegates to EnchantmentDefinitionsDebugCommand unchanged)
 *
 * Lore lines are 1-based in this command's own UI (matching how a player would count lines when
 * reading item tooltip text), converted to a 0-based List index internally.
 */
public class ItemCommand implements CommandExecutor {
    private final Plugin plugin;
    private final EnchantmentDefinitionsDebugCommand enchantmentsCommand;

    public ItemCommand(Plugin plugin, EnchantmentDefinitionsDebugCommand enchantmentsCommand) {
        this.plugin = plugin;
        this.enchantmentsCommand = enchantmentsCommand;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        return switch (sub) {
            case "rename" -> {
                handleRename(sender, rest);
                yield true;
            }
            case "lore" -> {
                handleLore(sender, rest);
                yield true;
            }
            case "enchantments", "enchantment" -> enchantmentsCommand.onCommand(sender, command, label, rest);
            default -> {
                sendUsage(sender);
                yield true;
            }
        };
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "Usage: /knk item rename <displayName> | " +
                "/knk item lore <add <text>|set <line> <text>|remove <line>> | " +
                "/knk item enchantments <list|vanilla|search|apply>");
    }

    private void handleRename(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk item rename <displayName>");
            return;
        }

        ItemStack heldItem = requireHeldItem(sender, player);
        if (heldItem == null) return;

        ItemMeta meta = requireItemMeta(sender, heldItem);
        if (meta == null) return;

        String rawName = String.join(" ", args);
        String formattedName = DisplayTextFormatter.translateToLegacy(rawName);
        meta.setDisplayName(formattedName);
        heldItem.setItemMeta(meta);
        player.getInventory().setItemInMainHand(heldItem);

        sender.sendMessage(ChatColor.GREEN + "Renamed held item to: " + formattedName);
    }

    private void handleLore(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) return;

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk item lore <add <text>|set <line> <text>|remove <line>>");
            return;
        }

        ItemStack heldItem = requireHeldItem(sender, player);
        if (heldItem == null) return;

        ItemMeta meta = requireItemMeta(sender, heldItem);
        if (meta == null) return;

        List<String> lore = meta.hasLore() && meta.getLore() != null
                ? new ArrayList<>(meta.getLore())
                : new ArrayList<>();

        String op = args[0].toLowerCase();
        switch (op) {
            case "add" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /knk item lore add <text>");
                    return;
                }
                String text = DisplayTextFormatter.translateToLegacy(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                lore.add(text);
                sender.sendMessage(ChatColor.GREEN + "Added lore line " + ChatColor.AQUA + lore.size() +
                        ChatColor.GREEN + ": " + text);
            }
            case "set" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /knk item lore set <line> <text>");
                    return;
                }
                Integer lineNumber = parsePositiveInt(args[1]);
                if (lineNumber == null) {
                    sender.sendMessage(ChatColor.RED + "Invalid line number: " + args[1]);
                    return;
                }
                String text = DisplayTextFormatter.translateToLegacy(String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                // 1-based -> 0-based. A line at or past the current end just appends (nothing to
                // push down); anything within range is inserted, shifting existing lines from
                // that point on down by one - not an in-place overwrite, per explicit request.
                int index = lineNumber - 1;
                if (index >= lore.size()) {
                    lore.add(text);
                    sender.sendMessage(ChatColor.GREEN + "Line " + ChatColor.AQUA + lineNumber +
                            ChatColor.GREEN + " was past the end - appended as line " + ChatColor.AQUA + lore.size() +
                            ChatColor.GREEN + ": " + text);
                } else {
                    lore.add(index, text);
                    sender.sendMessage(ChatColor.GREEN + "Inserted at line " + ChatColor.AQUA + lineNumber +
                            ChatColor.GREEN + " (existing lines from there shifted down): " + text);
                }
            }
            case "remove" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Usage: /knk item lore remove <line>");
                    return;
                }
                Integer lineNumber = parsePositiveInt(args[1]);
                int index = lineNumber != null ? lineNumber - 1 : -1;
                if (lineNumber == null || index < 0 || index >= lore.size()) {
                    sender.sendMessage(ChatColor.RED + "No lore line " + args[1] + " (item has " + lore.size() + " line(s)).");
                    return;
                }
                String removed = lore.remove(index);
                sender.sendMessage(ChatColor.GREEN + "Removed lore line " + ChatColor.AQUA + lineNumber +
                        ChatColor.GREEN + ": " + removed);
            }
            default -> {
                sender.sendMessage(ChatColor.YELLOW + "Usage: /knk item lore <add <text>|set <line> <text>|remove <line>>");
                return;
            }
        }

        meta.setLore(lore);
        heldItem.setItemMeta(meta);
        player.getInventory().setItemInMainHand(heldItem);
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(ChatColor.RED + "Only players can use this command.");
        return null;
    }

    private ItemStack requireHeldItem(CommandSender sender, Player player) {
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (heldItem.getType().isAir()) {
            sender.sendMessage(ChatColor.RED + "You must hold an item in your main hand.");
            return null;
        }
        return heldItem;
    }

    private ItemMeta requireItemMeta(CommandSender sender, ItemStack heldItem) {
        ItemMeta meta = heldItem.hasItemMeta() ? heldItem.getItemMeta() : null;
        if (meta == null) {
            meta = Bukkit.getItemFactory().getItemMeta(heldItem.getType());
        }
        if (meta == null) {
            sender.sendMessage(ChatColor.RED + "Unable to edit this item: item meta is unavailable.");
            return null;
        }
        return meta;
    }

    private Integer parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
