package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.core.domain.enchantment.CustomEnchantmentLore;
import net.knightsandkings.knk.api.impl.enchantment.LocalEnchantmentRepositoryImpl;
import net.knightsandkings.knk.core.dataaccess.EnchantmentDefinitionsDataAccess;
import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.enchantments.KnkEnchantmentDefinition;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprintDefaultEnchantment;
import net.knightsandkings.knk.core.domain.material.KnkMinecraftMaterialRef;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import net.knightsandkings.knk.paper.enchantbook.EnchantBookItems;
import net.knightsandkings.knk.paper.mapper.EnchantmentDefinitionBukkitMapper;
import net.knightsandkings.knk.paper.mapper.ItemBlueprintBukkitMapper;
import net.knightsandkings.knk.paper.utils.DisplayTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class ItemBlueprintsDebugCommand implements CommandExecutor {
    private final Plugin plugin;
    private final ItemBlueprintsDataAccess itemBlueprintsDataAccess;
    private final MinecraftMaterialRefsDataAccess minecraftMaterialRefsDataAccess;
    private final EnchantmentDefinitionsDataAccess enchantmentDefinitionsDataAccess;
    private final EnchantmentRepository customEnchantmentRepository;

    public ItemBlueprintsDebugCommand(
            Plugin plugin,
            ItemBlueprintsDataAccess itemBlueprintsDataAccess,
            MinecraftMaterialRefsDataAccess minecraftMaterialRefsDataAccess,
            EnchantmentDefinitionsDataAccess enchantmentDefinitionsDataAccess
    ) {
        this.plugin = plugin;
        this.itemBlueprintsDataAccess = itemBlueprintsDataAccess;
        this.minecraftMaterialRefsDataAccess = minecraftMaterialRefsDataAccess;
        this.enchantmentDefinitionsDataAccess = enchantmentDefinitionsDataAccess;
        this.customEnchantmentRepository = new LocalEnchantmentRepositoryImpl();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk itemblueprints list [page] [size] | " +
                    "/knk itemblueprints search <id|name|displayName> <value> [page] [size] | " +
                    "/knk itemblueprints get <id> | /knk itemblueprints give <id> [player]");
            return true;
        }

        String sub = args[0].toLowerCase();
        return switch (sub) {
            case "list" -> {
                handleList(sender, args);
                yield true;
            }
            case "search" -> {
                handleSearch(sender, args);
                yield true;
            }
            case "get", "id" -> {
                handleGet(sender, args);
                yield true;
            }
            case "give" -> {
                handleGive(sender, args);
                yield true;
            }
            default -> {
                sender.sendMessage(ChatColor.YELLOW + "Unknown subcommand. Use: list, search, get, give");
                yield true;
            }
        };
    }

    private void handleList(CommandSender sender, String[] args) {
        int page = args.length > 1 ? parseInt(args[1], 1) : 1;
        int size = args.length > 2 ? parseInt(args[2], 10) : 10;
        page = Math.max(1, page);
        size = Math.max(1, Math.min(size, 100));

        sender.sendMessage(ChatColor.GRAY + "Fetching item blueprints (page " + page + ", size " + size + ")...");

        itemBlueprintsDataAccess.listAsync(page, size)
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> printPage(sender, result)))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> printError(sender, ex));
                    return null;
                });
    }

    private void handleSearch(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk itemblueprints search <id|name|displayName> <value> [page] [size]");
            return;
        }

        String fieldInput = args[1];
        String valueInput = args[2];
        int page = args.length > 3 ? parseInt(args[3], 1) : 1;
        int size = args.length > 4 ? parseInt(args[4], 10) : 10;
        page = Math.max(1, page);
        size = Math.max(1, Math.min(size, 100));

        Map<String, String> filters = buildSearchFilters(fieldInput, valueInput);
        if (filters == null) {
            sender.sendMessage(ChatColor.RED + "Invalid field. Allowed: id, name, displayName");
            return;
        }

        PagedQuery query = new PagedQuery(page, size, null, null, false, filters);
        sender.sendMessage(ChatColor.GRAY + "Searching item blueprints by " + fieldInput + "...");

        itemBlueprintsDataAccess.searchAsync(query)
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> printPage(sender, result)))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> printError(sender, ex));
                    return null;
                });
    }

    private void handleGet(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk itemblueprints get <id>");
            return;
        }

        int id = parseInt(args[1], -1);
        if (id <= 0) {
            sender.sendMessage(ChatColor.RED + "Invalid id.");
            return;
        }

        sender.sendMessage(ChatColor.GRAY + "Fetching item blueprint id=" + id + "...");

        itemBlueprintsDataAccess.getByIdAsync(id)
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
                    KnkItemBlueprint itemBlueprint = result != null ? result.value().orElse(null) : null;
                    printDetail(sender, itemBlueprint);
                }))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> printError(sender, ex));
                    return null;
                });
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk itemblueprints give <id> [player]");
            return;
        }

        int id = parseInt(args[1], -1);
        if (id <= 0) {
            sender.sendMessage(ChatColor.RED + "Invalid id.");
            return;
        }

        Player targetPlayer;
        if (args.length > 2) {
            targetPlayer = Bukkit.getPlayerExact(args[2]);
            if (targetPlayer == null) {
                sender.sendMessage(ChatColor.RED + "Player not found or not online: " + args[2]);
                return;
            }
        } else if (sender instanceof Player playerSender) {
            targetPlayer = playerSender;
        } else {
            sender.sendMessage(ChatColor.RED + "Console must specify a target player.");
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk itemblueprints give <id> <player>");
            return;
        }

        sender.sendMessage(ChatColor.GRAY + "Generating item from blueprint id=" + id + "...");

        itemBlueprintsDataAccess.getByIdAsync(id)
                .thenCompose(result -> {
                    KnkItemBlueprint blueprint = result != null ? result.value().orElse(null) : null;
                    if (blueprint == null) {
                        return CompletableFuture.completedFuture(new GivePayload(null, null, Collections.emptyMap()));
                    }

                    CompletableFuture<String> materialNamespaceFuture = resolveMaterialNamespaceKey(blueprint);
                    CompletableFuture<Map<Integer, KnkEnchantmentDefinition>> definitionsFuture = fetchEnchantmentDefinitions(blueprint);

                    return materialNamespaceFuture.thenCombine(definitionsFuture,
                            (materialNamespaceKey, definitions) -> new GivePayload(blueprint, materialNamespaceKey, definitions));
                })
                .thenAccept(payload -> Bukkit.getScheduler().runTask(plugin, () -> executeGive(sender, targetPlayer, payload)))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> printError(sender, ex));
                    return null;
                });
    }

    private void executeGive(CommandSender sender, Player targetPlayer, GivePayload payload) {
        if (payload == null || payload.blueprint() == null) {
            sender.sendMessage(ChatColor.RED + "Item blueprint not found.");
            return;
        }

        if (payload.materialNamespaceKey() == null || payload.materialNamespaceKey().isBlank()) {
            sender.sendMessage(ChatColor.RED + "Blueprint has no resolvable material.");
            return;
        }

        final ItemStack itemStack;
        try {
            itemStack = ItemBlueprintBukkitMapper.fromBlueprint(payload.blueprint(), payload.materialNamespaceKey());
        } catch (Exception ex) {
            sender.sendMessage(ChatColor.RED + "Failed to map item blueprint to Bukkit item: " + ex.getMessage());
            return;
        }

        int applied = 0;
        List<String> skipped = new ArrayList<>();

        // A permanent enchantment book (KNG-5) teaches its default enchantment; it must never carry it itself,
        // not even when the book couldn't be resolved.
        List<KnkItemBlueprintDefaultEnchantment> defaultEnchantments = EnchantBookItems.isBookBlueprint(payload.blueprint(), itemStack.getType())
                ? Collections.emptyList()
                : safeEnchantments(payload.blueprint());

        for (KnkItemBlueprintDefaultEnchantment relation : defaultEnchantments) {
            if (relation == null || relation.enchantmentDefinitionId() == null) {
                continue;
            }

            KnkEnchantmentDefinition definition = payload.enchantmentDefinitions().get(relation.enchantmentDefinitionId());
            if (definition == null) {
                definition = EnchantmentDefinitionBukkitMapper.fromDefaultEnchantment(relation);
            }

            if (Boolean.TRUE.equals(definition.isCustom())) {
                EnchantmentDefinitionBukkitMapper.CustomEnchantmentResolution customResolution = EnchantmentDefinitionBukkitMapper.toCustom(definition);
                if (!customResolution.isValid()) {
                    skipped.add(relation.enchantmentDefinitionId() + " (" + customResolution.error() + ")");
                    continue;
                }

                int customLevel = relation.level() != null && relation.level() > 0 ? relation.level() : customResolution.defaultLevel();
                if (customLevel > customResolution.maxLevel()) {
                    skipped.add(relation.enchantmentDefinitionId() + " (level " + customLevel + " exceeds max " + customResolution.maxLevel() + ")");
                    continue;
                }

                if (applyCustomLoreEnchantment(itemStack, customResolution.enchantmentId(), customLevel)) {
                    applied++;
                } else {
                    skipped.add(relation.enchantmentDefinitionId() + " (failed to apply custom lore enchantment)");
                }

                continue;
            }

            EnchantmentDefinitionBukkitMapper.BukkitEnchantmentResolution resolution = EnchantmentDefinitionBukkitMapper.toBukkit(definition);
            if (!resolution.isValid()) {
                skipped.add(relation.enchantmentDefinitionId() + " (" + resolution.error() + ")");
                continue;
            }

            int level = relation.level() != null && relation.level() > 0 ? relation.level() : resolution.defaultLevel();
            itemStack.addUnsafeEnchantment(resolution.enchantment(), level);
            applied++;
        }

        reorderLoreEnchantmentsFirst(itemStack);

        Map<Integer, ItemStack> overflow = targetPlayer.getInventory().addItem(itemStack);
        if (!overflow.isEmpty()) {
            overflow.values().forEach(stack -> targetPlayer.getWorld().dropItemNaturally(targetPlayer.getLocation(), stack));
        }

        sender.sendMessage(ChatColor.GREEN + "Gave blueprint item to " + ChatColor.AQUA + targetPlayer.getName() +
            ChatColor.GREEN + ": " + ChatColor.WHITE + DisplayTextFormatter.translateToLegacy(safe(payload.blueprint().defaultDisplayName())));
        sender.sendMessage(ChatColor.GRAY + "Lore: " + ChatColor.WHITE +
            DisplayTextFormatter.translateToLegacy(safe(payload.blueprint().defaultDisplayDescription())));
        sender.sendMessage(ChatColor.GRAY + "Material: " + payload.materialNamespaceKey() + " | Applied enchantments: " + applied);
        if (!skipped.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Skipped enchantments: " + String.join(", ", skipped));
        }
    }

    private boolean applyCustomLoreEnchantment(ItemStack itemStack, String enchantmentId, int level) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return false;
        }

        ItemMeta itemMeta = itemStack.getItemMeta();
        List<String> lore = itemMeta != null && itemMeta.hasLore() ? itemMeta.getLore() : List.of();
        List<String> updatedLore = customEnchantmentRepository.applyEnchantment(lore, enchantmentId, level).join();

        if (itemMeta == null) {
            itemMeta = plugin.getServer().getItemFactory().getItemMeta(itemStack.getType());
        }

        if (itemMeta == null) {
            return false;
        }

        itemMeta.setLore(updatedLore);
        itemStack.setItemMeta(itemMeta);
        return true;
    }

    private void reorderLoreEnchantmentsFirst(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return;
        }

        ItemMeta itemMeta = itemStack.getItemMeta();
        List<String> lore = itemMeta != null && itemMeta.hasLore() ? itemMeta.getLore() : List.of();
        List<String> reorderedLore = reorderLoreEnchantmentsFirst(lore);
        if (reorderedLore.equals(lore)) {
            return;
        }

        if (itemMeta == null) {
            itemMeta = plugin.getServer().getItemFactory().getItemMeta(itemStack.getType());
        }

        if (itemMeta == null) {
            return;
        }

        itemMeta.setLore(reorderedLore);
        itemStack.setItemMeta(itemMeta);
    }

    private List<String> reorderLoreEnchantmentsFirst(List<String> loreLines) {
        return CustomEnchantmentLore.enchantmentsFirst(customEnchantmentRepository, loreLines);
    }

    private CompletableFuture<String> resolveMaterialNamespaceKey(KnkItemBlueprint blueprint) {
        if (blueprint.iconMaterialRefId() != null && blueprint.iconMaterialRefId() > 0) {
            return minecraftMaterialRefsDataAccess.getByIdAsync(blueprint.iconMaterialRefId())
                    .thenApply(result -> {
                        KnkMinecraftMaterialRef materialRef = result != null ? result.value().orElse(null) : null;
                        if (materialRef != null && materialRef.namespaceKey() != null && !materialRef.namespaceKey().isBlank()) {
                            return materialRef.namespaceKey();
                        }
                        return blueprint.iconNamespaceKey();
                    });
        }

        return CompletableFuture.completedFuture(blueprint.iconNamespaceKey());
    }

    private CompletableFuture<Map<Integer, KnkEnchantmentDefinition>> fetchEnchantmentDefinitions(KnkItemBlueprint blueprint) {
        List<KnkItemBlueprintDefaultEnchantment> enchantments = safeEnchantments(blueprint);
        if (enchantments.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        List<Integer> ids = enchantments.stream()
                .map(KnkItemBlueprintDefaultEnchantment::enchantmentDefinitionId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();

        if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        Map<Integer, CompletableFuture<FetchResult<KnkEnchantmentDefinition>>> futures = new LinkedHashMap<>();
        for (Integer id : ids) {
            futures.put(id, enchantmentDefinitionsDataAccess.getByIdAsync(id));
        }

        CompletableFuture<?>[] all = futures.values().toArray(new CompletableFuture[0]);
        return CompletableFuture.allOf(all).thenApply(unused -> {
            Map<Integer, KnkEnchantmentDefinition> resolved = new HashMap<>();
            futures.forEach((id, future) -> {
                try {
                    FetchResult<KnkEnchantmentDefinition> result = future.join();
                    result.value().ifPresent(definition -> resolved.put(id, definition));
                } catch (Exception ignored) {
                }
            });
            return resolved;
        });
    }

    private List<KnkItemBlueprintDefaultEnchantment> safeEnchantments(KnkItemBlueprint blueprint) {
        return blueprint != null && blueprint.defaultEnchantments() != null
                ? blueprint.defaultEnchantments()
                : Collections.emptyList();
    }

    private Map<String, String> buildSearchFilters(String fieldInput, String valueInput) {
        if (fieldInput == null || valueInput == null) {
            return null;
        }

        String normalized = fieldInput.trim().toLowerCase();
        Map<String, String> filters = new HashMap<>();

        switch (normalized) {
            case "id" -> {
                int idValue = parseInt(valueInput, -1);
                if (idValue <= 0) return null;
                filters.put("Id", String.valueOf(idValue));
                return filters;
            }
            case "name" -> {
                filters.put("Name", valueInput);
                return filters;
            }
            case "displayname", "display_name", "display-name" -> {
                filters.put("DefaultDisplayName", valueInput);
                return filters;
            }
            default -> {
                return null;
            }
        }
    }

    private void printPage(CommandSender sender, Page<KnkItemBlueprint> page) {
        sender.sendMessage(ChatColor.GREEN + "ItemBlueprints (" + page.items().size() + " of " + page.totalCount() + "):");
        for (KnkItemBlueprint itemBlueprint : page.items()) {
            String coloredDisplayName = DisplayTextFormatter.translateToLegacy(safe(itemBlueprint.defaultDisplayName()));
            sender.sendMessage(ChatColor.GRAY + "  [" + ChatColor.AQUA + itemBlueprint.id() + ChatColor.GRAY + "] " +
                    ChatColor.WHITE + safe(itemBlueprint.name()) + ChatColor.GRAY + " | display=" +
                    ChatColor.WHITE + coloredDisplayName + ChatColor.GRAY + " | enchCount=" +
                    (itemBlueprint.defaultEnchantmentsCount() != null ? itemBlueprint.defaultEnchantmentsCount() : 0));
        }

        if (page.items().isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "  (no item blueprints)");
        }

        int totalPages = page.pageSize() > 0
                ? Math.max(1, (int) Math.ceil((double) page.totalCount() / page.pageSize()))
                : 1;
        sender.sendMessage(ChatColor.GRAY + "Page " + page.pageNumber() + " of " + totalPages);
    }

    private void printDetail(CommandSender sender, KnkItemBlueprint itemBlueprint) {
        if (itemBlueprint == null) {
            sender.sendMessage(ChatColor.YELLOW + "No item blueprint found.");
            return;
        }

        sender.sendMessage(ChatColor.GREEN + "ItemBlueprint:");
        sender.sendMessage(ChatColor.GRAY + " id=" + itemBlueprint.id());
        sender.sendMessage(ChatColor.GRAY + " name=" + safe(itemBlueprint.name()));
        sender.sendMessage(ChatColor.GRAY + " displayName=" +
            DisplayTextFormatter.translateToLegacy(safe(itemBlueprint.defaultDisplayName())));
        sender.sendMessage(ChatColor.GRAY + " displayDescription=" +
            DisplayTextFormatter.translateToLegacy(safe(itemBlueprint.defaultDisplayDescription())));
        sender.sendMessage(ChatColor.GRAY + " quantity=" + (itemBlueprint.defaultQuantity() != null ? itemBlueprint.defaultQuantity() : 1));
        sender.sendMessage(ChatColor.GRAY + " maxStack=" + (itemBlueprint.maxStackSize() != null ? itemBlueprint.maxStackSize() : 64));
        sender.sendMessage(ChatColor.GRAY + " iconMaterialRefId=" + itemBlueprint.iconMaterialRefId() +
                " | iconNamespaceKey=" + safe(itemBlueprint.iconNamespaceKey()));
        sender.sendMessage(ChatColor.GRAY + " enchantments=" + safeEnchantments(itemBlueprint).size());
    }

    private void printError(CommandSender sender, Throwable ex) {
        Throwable cause = ex.getCause();

        if (cause instanceof ApiException apiEx) {
            if (apiEx.getStatusCode() > 0) {
                sender.sendMessage(ChatColor.RED + "HTTP " + apiEx.getStatusCode());
                if (apiEx.getRequestUrl() != null) {
                    sender.sendMessage(ChatColor.RED + "URL: " + apiEx.getRequestUrl());
                }
                if (apiEx.getResponseBody() != null && !apiEx.getResponseBody().isEmpty()) {
                    sender.sendMessage(ChatColor.RED + apiEx.getResponseBody());
                }
            } else if (apiEx.getCause() != null) {
                sender.sendMessage(ChatColor.RED + apiEx.getCause().getClass().getSimpleName() + ": " + apiEx.getCause().getMessage());
            } else {
                sender.sendMessage(ChatColor.RED + apiEx.getMessage());
            }
        } else {
            sender.sendMessage(ChatColor.RED + (cause != null ? cause.getMessage() : ex.getMessage()));
        }
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private record GivePayload(
            KnkItemBlueprint blueprint,
            String materialNamespaceKey,
            Map<Integer, KnkEnchantmentDefinition> enchantmentDefinitions
    ) {}
}
