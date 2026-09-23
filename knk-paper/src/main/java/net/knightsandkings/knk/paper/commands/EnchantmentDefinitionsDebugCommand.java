package net.knightsandkings.knk.paper.commands;

import net.knightsandkings.knk.api.impl.enchantment.LocalEnchantmentRepositoryImpl;
import net.knightsandkings.knk.core.dataaccess.EnchantmentDefinitionsDataAccess;
import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.enchantments.KnkEnchantmentDefinition;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import net.knightsandkings.knk.paper.mapper.EnchantmentDefinitionBukkitMapper;
import net.knightsandkings.knk.paper.utils.DisplayTextFormatter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Command handler for /knk enchantments.
 *
 * Supports:
 * - /knk enchantments list [page] [size]
 * - /knk enchantments vanilla [page] [size]
 * - /knk enchantments search <id|key|displayName> <value> [page] [size]
 * - /knk enchantments apply <id|vanillaName|customKey> [level]
 */
public class EnchantmentDefinitionsDebugCommand implements CommandExecutor {
    private final Plugin plugin;
    private final EnchantmentDefinitionsDataAccess dataAccess;
    private final EnchantmentRepository customEnchantmentRepository;

    public EnchantmentDefinitionsDebugCommand(Plugin plugin, EnchantmentDefinitionsDataAccess dataAccess) {
        this.plugin = plugin;
        this.dataAccess = dataAccess;
        this.customEnchantmentRepository = new LocalEnchantmentRepositoryImpl();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk enchantments list [page] [size] | " +
                    "/knk enchantments vanilla [page] [size] | " +
                    "/knk enchantments search <id|key|displayName> <value> [page] [size] | " +
                    "/knk enchantments apply <id|vanillaName|customKey> [level]");
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
            case "vanilla" -> {
                handleVanillaList(sender, args);
                yield true;
            }
            case "apply" -> {
                handleApply(sender, args);
                yield true;
            }
            default -> {
                sender.sendMessage(ChatColor.YELLOW + "Unknown subcommand. Use: list, vanilla, search, apply");
                yield true;
            }
        };
    }

    private void handleList(CommandSender sender, String[] args) {
        int page = args.length > 1 ? parseInt(args[1], 1) : 1;
        int size = args.length > 2 ? parseInt(args[2], 10) : 10;
        page = Math.max(1, page);
        size = Math.max(1, Math.min(size, 100));

        sender.sendMessage(ChatColor.GRAY + "Fetching enchantments (page " + page + ", size " + size + ")...");

        dataAccess.listAsync(page, size)
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> printPage(sender, result)))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> printError(sender, ex));
                    return null;
                });
    }

    private void handleSearch(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk enchantments search <id|key|displayName> <value> [page] [size]");
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
            sender.sendMessage(ChatColor.RED + "Invalid field. Allowed: id, key, displayName");
            return;
        }

        PagedQuery query = new PagedQuery(page, size, null, null, false, filters);
        sender.sendMessage(ChatColor.GRAY + "Searching enchantments by " + fieldInput + "...");

        dataAccess.searchAsync(query)
                .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> printPage(sender, result)))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> printError(sender, ex));
                    return null;
                });
    }

    private void handleApply(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk enchantments apply <id|vanillaName|customKey> [level]");
            return;
        }

        int requestedLevel = -1;
        int targetEndExclusive = args.length;
        if (args.length > 2) {
            int maybeLevel = parseInt(args[args.length - 1], -1);
            if (maybeLevel > 0) {
                requestedLevel = maybeLevel;
                targetEndExclusive = args.length - 1;
            }
        }

        String target = String.join(" ", java.util.Arrays.copyOfRange(args, 1, targetEndExclusive)).trim();
        if (target.isBlank()) {
            sender.sendMessage(ChatColor.RED + "Missing enchantment target.");
            sender.sendMessage(ChatColor.YELLOW + "Usage: /knk enchantments apply <id|vanillaName|customKey> [level]");
            return;
        }

        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (heldItem == null || heldItem.getType().isAir()) {
            sender.sendMessage(ChatColor.RED + "You must hold an item in your main hand.");
            return;
        }

        final int finalRequestedLevel = requestedLevel;

        int enchantmentDefinitionId = parseInt(target, -1);
        if (enchantmentDefinitionId > 0) {
            sender.sendMessage(ChatColor.GRAY + "Fetching enchantment definition " + enchantmentDefinitionId + "...");

            dataAccess.getByIdAsync(enchantmentDefinitionId)
                    .thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> applyKnkEnchantment(sender, player, result, finalRequestedLevel)))
                    .exceptionally(ex -> {
                        Bukkit.getScheduler().runTask(plugin, () -> printError(sender, ex));
                        return null;
                    });
            return;
        }

        sender.sendMessage(ChatColor.GRAY + "Resolving custom/vanilla enchantment for '" + target + "'...");
        resolveCustomDefinitionByTargetAsync(target)
                .thenAccept(definition -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (definition != null) {
                        applyCustomEnchantment(sender, player, definition, finalRequestedLevel);
                        return;
                    }

                    applyVanillaEnchantment(sender, player, target, finalRequestedLevel);
                }))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> printError(sender, ex));
                    return null;
                });
    }

    private CompletableFuture<KnkEnchantmentDefinition> resolveCustomDefinitionByTargetAsync(String target) {
        String trimmedTarget = target != null ? target.trim() : "";
        if (trimmedTarget.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        Set<String> keyCandidates = buildCustomKeyCandidates(trimmedTarget);
        CompletableFuture<KnkEnchantmentDefinition> search = CompletableFuture.completedFuture(null);

        for (String keyCandidate : keyCandidates) {
            search = search.thenCompose(found -> {
                if (found != null) {
                    return CompletableFuture.completedFuture(found);
                }

                return searchCustomByFieldAsync("Key", keyCandidate)
                        .thenApply(page -> pickCustomDefinitionMatch(page, trimmedTarget));
            });
        }

        return search.thenCompose(found -> {
            if (found != null) {
                return CompletableFuture.completedFuture(found);
            }

            return searchCustomByFieldAsync("DisplayName", trimmedTarget)
                    .thenApply(page -> pickCustomDefinitionMatch(page, trimmedTarget));
        });
    }

    private CompletableFuture<Page<KnkEnchantmentDefinition>> searchCustomByFieldAsync(String fieldName, String value) {
        Map<String, String> filters = new HashMap<>();
        filters.put(fieldName, value);
        PagedQuery query = new PagedQuery(1, 100, null, null, false, filters);
        return dataAccess.searchAsync(query);
    }

    private KnkEnchantmentDefinition pickCustomDefinitionMatch(Page<KnkEnchantmentDefinition> page, String target) {
        if (page == null || page.items() == null || page.items().isEmpty()) {
            return null;
        }

        String normalizedTarget = normalizeCustomLookup(target);

        for (KnkEnchantmentDefinition definition : page.items()) {
            if (definition == null || !Boolean.TRUE.equals(definition.isCustom())) {
                continue;
            }

            if (matchesCustomTarget(definition, normalizedTarget)) {
                return definition;
            }
        }

        // No unconditional "return whatever custom definition happens to be in the results"
        // fallback here - that used to exist and, combined with the backend previously ignoring
        // PagedQuery.Filters entirely (see EnchantmentDefinitionRepository.SearchAsync), meant
        // /knk enchantments apply sharpness silently resolved to an arbitrary unrelated custom
        // enchantment (e.g. armor_repair) instead of falling through to vanilla resolution below.
        // No match in this result set means no match, full stop.
        return null;
    }

    private boolean matchesCustomTarget(KnkEnchantmentDefinition definition, String normalizedTarget) {
        if (normalizedTarget == null || normalizedTarget.isBlank() || definition == null) {
            return false;
        }

        String key = normalizeCustomLookup(definition.key());
        String keyPart = normalizeCustomLookup(extractKeyPart(definition.key()));
        String displayName = normalizeCustomLookup(definition.displayName());

        return normalizedTarget.equals(key)
                || normalizedTarget.equals(keyPart)
                || normalizedTarget.equals(displayName);
    }

    private Set<String> buildCustomKeyCandidates(String target) {
        Set<String> candidates = new LinkedHashSet<>();
        String trimmed = target.trim();
        String lowered = trimmed.toLowerCase(Locale.ROOT);
        String normalized = lowered.replace(' ', '_').replace('-', '_');

        candidates.add(trimmed);
        candidates.add(lowered);
        candidates.add(normalized);

        if (!normalized.contains(":")) {
            candidates.add("knk:" + normalized);
        }

        return candidates;
    }

    private String extractKeyPart(String key) {
        if (key == null) {
            return null;
        }

        int separator = key.indexOf(':');
        if (separator < 0 || separator >= key.length() - 1) {
            return key;
        }

        return key.substring(separator + 1);
    }

    private String normalizeCustomLookup(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private void handleVanillaList(CommandSender sender, String[] args) {
        int page = args.length > 1 ? parseInt(args[1], 1) : 1;
        int size = args.length > 2 ? parseInt(args[2], 10) : 10;
        page = Math.max(1, page);
        size = Math.max(1, Math.min(size, 100));

        List<Enchantment> enchantments = getSortedVanillaEnchantments();
        if (enchantments.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No vanilla enchantments available from registry.");
            return;
        }

        int totalCount = enchantments.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalCount / size));
        if (page > totalPages) {
            page = totalPages;
        }

        int startIndex = (page - 1) * size;
        int endIndex = Math.min(startIndex + size, totalCount);

        sender.sendMessage(ChatColor.GREEN + "Vanilla enchantments (" + totalCount + " total):");
        for (int index = startIndex; index < endIndex; index++) {
            Enchantment enchantment = enchantments.get(index);
            String namespaceKey = enchantment.getKey().toString();
            String displayName = toDisplayName(enchantment);
            sender.sendMessage(ChatColor.GRAY + "  - " + ChatColor.WHITE + displayName +
                    ChatColor.GRAY + " [" + namespaceKey + "]");
        }

        sender.sendMessage(ChatColor.GRAY + "Page " + page + " of " + totalPages +
                ChatColor.GRAY + " | Use: /knk enchantments vanilla [page] [size]");
    }

    private void applyKnkEnchantment(CommandSender sender, Player player, FetchResult<KnkEnchantmentDefinition> result, int requestedLevel) {
        KnkEnchantmentDefinition definition = result != null ? result.value().orElse(null) : null;
        if (definition == null) {
            sender.sendMessage(ChatColor.RED + "Enchantment definition not found.");
            return;
        }

        if (Boolean.TRUE.equals(definition.isCustom())) {
            applyCustomEnchantment(sender, player, definition, requestedLevel);
            return;
        }

        EnchantmentDefinitionBukkitMapper.BukkitEnchantmentResolution resolution = EnchantmentDefinitionBukkitMapper.toBukkit(definition);
        if (!resolution.isValid()) {
            sender.sendMessage(ChatColor.RED + "Unable to apply enchantment: " + resolution.error());
            return;
        }

        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (heldItem == null || heldItem.getType().isAir()) {
            sender.sendMessage(ChatColor.RED + "You are no longer holding a valid item.");
            return;
        }

        int level = requestedLevel > 0 ? requestedLevel : resolution.defaultLevel();

        heldItem.addUnsafeEnchantment(resolution.enchantment(), level);
        player.getInventory().setItemInMainHand(heldItem);

        sender.sendMessage(ChatColor.GREEN + "Applied " + ChatColor.AQUA + resolution.namespaceKey() +
                ChatColor.GREEN + " level " + ChatColor.AQUA + level +
                ChatColor.GREEN + " from KnK definition to your held item.");
    }

    private void applyCustomEnchantment(CommandSender sender, Player player, KnkEnchantmentDefinition definition, int requestedLevel) {
        EnchantmentDefinitionBukkitMapper.CustomEnchantmentResolution customResolution = EnchantmentDefinitionBukkitMapper.toCustom(definition);
        if (!customResolution.isValid()) {
            sender.sendMessage(ChatColor.RED + "Unable to apply custom enchantment: " + customResolution.error());
            return;
        }

        int level = requestedLevel > 0 ? requestedLevel : customResolution.defaultLevel();
        if (level > customResolution.maxLevel()) {
            sender.sendMessage(ChatColor.RED + "Cannot apply custom enchantment " +
                    ChatColor.AQUA + customResolution.enchantmentId() + ChatColor.RED +
                    ": max level is " + ChatColor.AQUA + customResolution.maxLevel());
            return;
        }

        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (heldItem == null || heldItem.getType().isAir()) {
            sender.sendMessage(ChatColor.RED + "You are no longer holding a valid item.");
            return;
        }

        ItemMeta itemMeta = heldItem.getItemMeta();
        List<String> lore = itemMeta != null && itemMeta.hasLore() ? itemMeta.getLore() : List.of();
        List<String> updatedLore = customEnchantmentRepository
                .applyEnchantment(lore, customResolution.enchantmentId(), level)
                .join();
        updatedLore = reorderLoreEnchantmentsFirst(updatedLore);

        if (itemMeta == null) {
            itemMeta = plugin.getServer().getItemFactory().getItemMeta(heldItem.getType());
        }

        if (itemMeta == null) {
            sender.sendMessage(ChatColor.RED + "Unable to apply custom enchantment: item meta is unavailable.");
            return;
        }

        itemMeta.setLore(updatedLore);
        heldItem.setItemMeta(itemMeta);
        player.getInventory().setItemInMainHand(heldItem);

        sender.sendMessage(ChatColor.GREEN + "Applied custom " + ChatColor.AQUA + customResolution.enchantmentId() +
                ChatColor.GREEN + " level " + ChatColor.AQUA + level +
                ChatColor.GREEN + " from KnK definition to your held item.");
    }

    private List<String> reorderLoreEnchantmentsFirst(List<String> loreLines) {
        if (loreLines == null || loreLines.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> enchantments = customEnchantmentRepository.getEnchantments(loreLines).join();
        if (enchantments.isEmpty()) {
            return loreLines;
        }

        List<String> nonEnchantmentLore = loreLines;
        for (String enchantmentId : enchantments.keySet()) {
            nonEnchantmentLore = customEnchantmentRepository.removeEnchantment(nonEnchantmentLore, enchantmentId).join();
        }

        List<String> enchantmentLore = List.of();
        for (Map.Entry<String, Integer> enchantmentEntry : enchantments.entrySet()) {
            int enchantmentLevel = enchantmentEntry.getValue() != null && enchantmentEntry.getValue() > 0
                    ? enchantmentEntry.getValue()
                    : 1;
            enchantmentLore = customEnchantmentRepository
                    .applyEnchantment(enchantmentLore, enchantmentEntry.getKey(), enchantmentLevel)
                    .join();
        }

        List<String> reorderedLore = new ArrayList<>(enchantmentLore);
        reorderedLore.addAll(nonEnchantmentLore);
        return reorderedLore;
    }

    private void applyVanillaEnchantment(CommandSender sender, Player player, String target, int requestedLevel) {
        Enchantment enchantment = resolveVanillaEnchantment(target);
        if (enchantment == null) {
            sender.sendMessage(ChatColor.RED + "Vanilla enchantment not found: " + target);
            sender.sendMessage(ChatColor.GRAY + "Tip: use /knk enchantments vanilla to list names, or pass a numeric definition id for custom enchantments.");
            return;
        }

        ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (heldItem == null || heldItem.getType().isAir()) {
            sender.sendMessage(ChatColor.RED + "You are no longer holding a valid item.");
            return;
        }

        int level = requestedLevel > 0 ? requestedLevel : 1;

        heldItem.addUnsafeEnchantment(enchantment, level);
        player.getInventory().setItemInMainHand(heldItem);

        sender.sendMessage(ChatColor.GREEN + "Applied vanilla " + ChatColor.AQUA + enchantment.getKey() +
                ChatColor.GREEN + " level " + ChatColor.AQUA + level +
                ChatColor.GREEN + " to your held item.");
    }

    private Enchantment resolveVanillaEnchantment(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String trimmed = input.trim();
        String lowered = trimmed.toLowerCase(Locale.ROOT);

        NamespacedKey directKey = NamespacedKey.fromString(lowered);
        if (directKey != null) {
            Enchantment direct = Registry.ENCHANTMENT.get(directKey);
            if (direct != null) {
                return direct;
            }
        }

        String keyPartCandidate = lowered
                .replace(' ', '_')
                .replace('-', '_');
        NamespacedKey minecraftKey = NamespacedKey.minecraft(keyPartCandidate);
        Enchantment byKeyPart = Registry.ENCHANTMENT.get(minecraftKey);
        if (byKeyPart != null) {
            return byKeyPart;
        }

        String normalizedInput = normalizeText(trimmed);
        for (Enchantment enchantment : getSortedVanillaEnchantments()) {
            String ns = enchantment.getKey().toString();
            String keyPart = enchantment.getKey().getKey();
            String display = toDisplayName(enchantment);

            if (normalizeText(ns).equals(normalizedInput)
                    || normalizeText(keyPart).equals(normalizedInput)
                    || normalizeText(display).equals(normalizedInput)) {
                return enchantment;
            }
        }

        return null;
    }

    private List<Enchantment> getSortedVanillaEnchantments() {
        List<Enchantment> result = new ArrayList<>();
        for (Enchantment enchantment : Registry.ENCHANTMENT) {
            if (enchantment != null && enchantment.getKey() != null
                    && "minecraft".equals(enchantment.getKey().getNamespace())) {
                result.add(enchantment);
            }
        }
        result.sort(Comparator.comparing(e -> e.getKey().toString()));
        return result;
    }

    private String toDisplayName(Enchantment enchantment) {
        String keyPart = enchantment.getKey().getKey();
        String[] words = keyPart.split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                builder.append(word.substring(1));
            }
        }
        return builder.toString();
    }

    private String normalizeText(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private void printPage(CommandSender sender, Page<KnkEnchantmentDefinition> page) {
        sender.sendMessage(ChatColor.GREEN + "Enchantments (" + page.items().size() + " of " + page.totalCount() + "):");
        for (KnkEnchantmentDefinition definition : page.items()) {
            String namespace = definition.baseEnchantmentNamespaceKey() != null
                    ? definition.baseEnchantmentNamespaceKey()
                    : "-";
            String displayName = formatText(definition.displayName(), "-");

            sender.sendMessage(ChatColor.GRAY + "  [" + ChatColor.AQUA + definition.id() + ChatColor.GRAY + "] " +
                    ChatColor.WHITE + safe(definition.key()) + ChatColor.GRAY + " | " +
                ChatColor.WHITE + displayName + ChatColor.GRAY +
                    " | maxLevel=" + (definition.maxLevel() != null ? definition.maxLevel() : 1) +
                    " | type=" + (Boolean.TRUE.equals(definition.isCustom()) ? "custom" : "vanilla") +
                    " | base=" + namespace);
        }

        if (page.items().isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "  (no enchantments)");
        }

        int totalPages = page.pageSize() > 0 ? Math.max(1, (int) Math.ceil((double) page.totalCount() / page.pageSize())) : 1;
        sender.sendMessage(ChatColor.GRAY + "Page " + page.pageNumber() + " of " + totalPages);
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
            case "key" -> {
                filters.put("Key", valueInput);
                return filters;
            }
            case "displayname", "display_name", "display-name" -> {
                filters.put("DisplayName", valueInput);
                return filters;
            }
            default -> {
                return null;
            }
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
        return value != null ? value : "-";
    }

    private String formatText(String value, String fallback) {
        String raw = (value == null || value.isBlank()) ? fallback : value;
        return DisplayTextFormatter.translateToLegacy(raw);
    }
}
