package net.knightsandkings.knk.paper.siege.survival.items;

import net.knightsandkings.knk.core.dataaccess.EnchantmentDefinitionsDataAccess;
import net.knightsandkings.knk.core.dataaccess.ItemBlueprintsDataAccess;
import net.knightsandkings.knk.core.dataaccess.MinecraftMaterialRefsDataAccess;
import net.knightsandkings.knk.core.domain.enchantments.KnkEnchantmentDefinition;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.paper.mapper.EnchantmentDefinitionBukkitMapper;
import net.knightsandkings.knk.paper.item.BlueprintItemAssembler;
import net.knightsandkings.knk.paper.item.BlueprintItemAssembler.EnchantmentRequest;
import net.knightsandkings.knk.paper.kit.KitGrantPlacer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds the match items of phase 4 (purchase offers, Mystery Box prizes and reel faces; DESIGN §8, §8a) from the
 * catalogue: a blueprint through {@link BlueprintItemAssembler#assemble} with its default enchantments plus the rolled
 * ones (definition id + level, S7a), or an enchanted book for a book entry. Everything is tagged as a match item (§8b).
 * Resolution runs off the main thread (the cache-first data accesses), like the kit placer.
 */
public final class SurvivalItemFactory {

    /** A rolled enchantment of an item entry (the S7a {@code rolledEnchantments}). */
    public record Rolled(int enchantmentDefinitionId, int level) { }

    private final ItemBlueprintsDataAccess blueprints;
    private final MinecraftMaterialRefsDataAccess materials;
    private final EnchantmentDefinitionsDataAccess enchantments;
    private final BlueprintItemAssembler assembler;
    private final MatchItems items;
    private final Logger logger;

    public SurvivalItemFactory(ItemBlueprintsDataAccess blueprints, MinecraftMaterialRefsDataAccess materials,
                               EnchantmentDefinitionsDataAccess enchantments, BlueprintItemAssembler assembler, MatchItems items, Logger logger) {
        this.blueprints = Objects.requireNonNull(blueprints, "blueprints");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.enchantments = enchantments;
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.items = Objects.requireNonNull(items, "items");
        this.logger = logger;
    }

    public MatchItems items() {
        return items;
    }

    /**
     * The blueprint's item with its defaults and the rolled enchantments, {@code quantity} (null = the blueprint's default),
     * tagged for {@code matchKey}; empty when the blueprint or its material is unknown.
     */
    public CompletableFuture<Optional<ItemStack>> blueprintItem(int blueprintId, Integer quantity, List<Rolled> rolled, String matchKey) {
        return blueprints.getByIdAsync(blueprintId).thenCompose(result -> {
            KnkItemBlueprint blueprint = result == null ? null : result.value().orElse(null);
            if (blueprint == null) {
                warn("ItemBlueprint " + blueprintId + " not found");
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return KitGrantPlacer.resolveMaterialNamespaceKey(blueprint, materials).thenCompose(materialKey -> {
                if (materialKey == null || materialKey.isBlank()) {
                    warn("ItemBlueprint " + blueprintId + " has no resolvable material");
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                return rolledRequests(rolled).thenApply(requests -> {
                    try {
                        BlueprintItemAssembler.Options options = BlueprintItemAssembler.Options.DEFAULTS.withVanillaRules(true)
                            .withMetaStamp(items.stamp(matchKey));
                        BlueprintItemAssembler.Result assembled = assembler.assemble(blueprint, materialKey,
                            BlueprintItemAssembler.defaultEnchantments(blueprint, Map.of()), requests, options, quantity);
                        if (!assembled.skipped().isEmpty()) warn("ItemBlueprint " + blueprintId + " skipped enchantments: " + String.join(", ", assembled.skipped()));
                        return Optional.of(items.tag(assembled.itemStack(), matchKey));
                    } catch (RuntimeException e) {
                        if (logger != null) logger.log(Level.WARNING, "[Survival] ItemBlueprint " + blueprintId + " could not be assembled", e);
                        return Optional.empty();
                    }
                });
            });
        });
    }

    /** An enchanted book with a vanilla enchantment definition; a custom definition becomes a labelled book (flag S7b). */
    public CompletableFuture<Optional<ItemStack>> enchantedBook(int definitionId, int level, String fallbackKey, String matchKey) {
        if (enchantments == null) return CompletableFuture.completedFuture(Optional.of(labelledBook(fallbackKey, level, matchKey)));
        return enchantments.getByIdAsync(definitionId).handle((result, ex) -> {
            KnkEnchantmentDefinition definition = ex == null && result != null ? result.value().orElse(null) : null;
            if (definition == null) {
                warn("EnchantmentDefinition " + definitionId + " not found; the book shows the key only");
                return Optional.of(labelledBook(fallbackKey, level, matchKey));
            }
            EnchantmentDefinitionBukkitMapper.BukkitEnchantmentResolution resolution = EnchantmentDefinitionBukkitMapper.toBukkit(definition);
            if (!resolution.isValid() || resolution.enchantment() == null) {
                warn("EnchantmentDefinition " + definitionId + " (" + definition.key() + ") is not a vanilla enchantment: " + resolution.error()
                    + "; the book shows the key only");
                return Optional.of(labelledBook(definition.displayName() != null ? definition.displayName() : definition.key(), level, matchKey));
            }
            return Optional.of(storedBook(resolution.enchantment(), Math.max(1, level), matchKey));
        });
    }

    private CompletableFuture<List<EnchantmentRequest>> rolledRequests(List<Rolled> rolled) {
        if (rolled == null || rolled.isEmpty() || enchantments == null) return CompletableFuture.completedFuture(List.of());
        List<CompletableFuture<EnchantmentRequest>> futures = new ArrayList<>();
        for (Rolled r : rolled) {
            futures.add(enchantments.getByIdAsync(r.enchantmentDefinitionId()).handle((result, ex) -> {
                KnkEnchantmentDefinition definition = ex == null && result != null ? result.value().orElse(null) : null;
                if (definition == null) {
                    warn("rolled EnchantmentDefinition " + r.enchantmentDefinitionId() + " not found; skipped");
                    return null;
                }
                return new EnchantmentRequest(r.enchantmentDefinitionId(), definition, Math.max(1, r.level()));
            }));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList());
    }

    ItemStack storedBook(Enchantment enchantment, int level, String matchKey) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(enchantment, level, true);
        meta.lore(List.of(Component.text("Mystery Box prize; removed after the match.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        book.setItemMeta(meta);
        return items.tag(book, matchKey);
    }

    ItemStack labelledBook(String key, int level, String matchKey) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        var meta = book.getItemMeta();
        meta.displayName(Component.text((key == null || key.isBlank() ? "Enchantment" : key) + " " + level, NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Mystery Box prize; removed after the match.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        book.setItemMeta(meta);
        return items.tag(book, matchKey);
    }

    private void warn(String message) {
        if (logger != null) logger.warning("[Survival] " + message);
    }
}
