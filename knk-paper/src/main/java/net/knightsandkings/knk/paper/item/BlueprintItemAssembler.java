package net.knightsandkings.knk.paper.item;

import net.knightsandkings.knk.core.domain.enchantment.CustomEnchantmentLore;
import net.knightsandkings.knk.core.domain.enchantments.KnkEnchantmentDefinition;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprintDefaultEnchantment;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import net.knightsandkings.knk.paper.enchantbook.EnchantBookItems;
import net.knightsandkings.knk.paper.mapper.EnchantmentDefinitionBukkitMapper;
import net.knightsandkings.knk.paper.mapper.ItemBlueprintBukkitMapper;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Blueprint plus an enchantment list -&gt; a finished {@link ItemStack} (docs/specs/lootboxes/DESIGN.md §3.4,
 * IMPLEMENTATION_PLAN.md Phase 0). Extracted from {@code /knk itemblueprints give} so lootbox delivery, kits and
 * that command build items the same way:
 * <ol>
 *   <li>{@link ItemBlueprintBukkitMapper#fromBlueprint}: name, lore, grade line and tag, origin line, and the
 *       permanent enchantment book decoration.</li>
 *   <li>Each enchantment: vanilla ones via {@link ItemStack#addUnsafeEnchantment} (v1 allowed levels above the
 *       vanilla max), custom ones as lore via {@link EnchantmentRepository#applyEnchantment}. A custom level above
 *       its max is skipped. With {@link Options#vanillaRules()} on, a vanilla enchantment the item can't take
 *       ({@code canEnchantItem}) or that conflicts with one already on it ({@code conflictsWith}) is skipped too.</li>
 *   <li>Custom enchantment lines moved to the top of the lore ({@link CustomEnchantmentLore#enchantmentsFirst}).</li>
 *   <li>{@link Options#metaStamp()}, if any: the caller's extra PDC tags (the lootbox item-instance id and rolled
 *       grade, Phase 3).</li>
 * </ol>
 * A permanent enchantment book blueprint (KNG-5) never gets enchantments: it teaches its enchantment, it doesn't
 * carry it. Skipped enchantments are reported in {@link Result#skipped()}, never thrown.
 * <p>
 * Touches only the new {@link ItemStack}, never a player or world, so it can run off the main thread (kits build
 * items in their async resolve step).
 */
public final class BlueprintItemAssembler {

    /** One enchantment to apply. {@code level} null or &lt;= 0 means the definition's default level. */
    public record EnchantmentRequest(Integer definitionId, KnkEnchantmentDefinition definition, Integer level) {
    }

    /**
     * How to assemble.
     *
     * @param vanillaRules skip vanilla enchantments that fail {@code canEnchantItem} / {@code conflictsWith}. Off for
     *                     blueprint defaults (admin-authored, applied as before); on for server-rolled lootbox
     *                     enchantments.
     * @param metaStamp    optional last step on the item's meta, e.g. extra PDC tags; null for none
     */
    public record Options(boolean vanillaRules, Consumer<ItemMeta> metaStamp) {
        /** Blueprint defaults, as {@code /knk itemblueprints give} has always applied them. */
        public static final Options DEFAULTS = new Options(false, null);

        public Options withVanillaRules(boolean enabled) {
            return new Options(enabled, metaStamp);
        }

        public Options withMetaStamp(Consumer<ItemMeta> stamp) {
            return new Options(vanillaRules, stamp);
        }
    }

    /** The built item, how many enchantments landed on it, and why the others didn't ({@code "<definitionId> (<reason>)"}). */
    public record Result(ItemStack itemStack, int applied, List<String> skipped) {
    }

    /** A resolved vanilla enchantment; {@code handle} is the Bukkit enchantment (null in unit tests). */
    record VanillaEnchantment(String namespaceKey, int defaultLevel, Enchantment handle, String error) {
        boolean isValid() {
            return error == null && namespaceKey != null;
        }

        static VanillaEnchantment invalid(String error) {
            return new VanillaEnchantment(null, 0, null, error);
        }
    }

    /**
     * The Bukkit-registry side of vanilla enchanting. A seam only so the assembler can be unit tested without a
     * server ({@link Enchantment}'s static initialiser needs a live registry).
     */
    interface VanillaEnchanter {
        VanillaEnchantment resolve(KnkEnchantmentDefinition definition);

        boolean canEnchantItem(VanillaEnchantment enchantment, ItemStack itemStack);

        boolean conflictsWithItem(VanillaEnchantment enchantment, ItemStack itemStack);

        void apply(VanillaEnchantment enchantment, ItemStack itemStack, int level);
    }

    private static final VanillaEnchanter BUKKIT = new VanillaEnchanter() {
        @Override
        public VanillaEnchantment resolve(KnkEnchantmentDefinition definition) {
            EnchantmentDefinitionBukkitMapper.BukkitEnchantmentResolution resolution = EnchantmentDefinitionBukkitMapper.toBukkit(definition);
            return resolution.isValid()
                    ? new VanillaEnchantment(resolution.namespaceKey(), resolution.defaultLevel(), resolution.enchantment(), null)
                    : VanillaEnchantment.invalid(resolution.error());
        }

        @Override
        public boolean canEnchantItem(VanillaEnchantment enchantment, ItemStack itemStack) {
            return enchantment.handle().canEnchantItem(itemStack);
        }

        @Override
        public boolean conflictsWithItem(VanillaEnchantment enchantment, ItemStack itemStack) {
            // conflictsWith(self) is true in vanilla; re-applying the same enchantment just sets its level.
            return itemStack.getEnchantments().keySet().stream()
                    .anyMatch(existing -> !existing.equals(enchantment.handle()) && existing.conflictsWith(enchantment.handle()));
        }

        @Override
        public void apply(VanillaEnchantment enchantment, ItemStack itemStack, int level) {
            itemStack.addUnsafeEnchantment(enchantment.handle(), level);
        }
    };

    private final EnchantmentRepository customEnchantmentRepository;
    private final BiFunction<KnkItemBlueprint, String, ItemStack> itemFactory;
    private final VanillaEnchanter vanillaEnchanter;

    public BlueprintItemAssembler(EnchantmentRepository customEnchantmentRepository) {
        this(customEnchantmentRepository, ItemBlueprintBukkitMapper::fromBlueprint, BUKKIT);
    }

    BlueprintItemAssembler(
            EnchantmentRepository customEnchantmentRepository,
            BiFunction<KnkItemBlueprint, String, ItemStack> itemFactory,
            VanillaEnchanter vanillaEnchanter
    ) {
        this.customEnchantmentRepository = Objects.requireNonNull(customEnchantmentRepository, "customEnchantmentRepository must not be null");
        this.itemFactory = Objects.requireNonNull(itemFactory, "itemFactory must not be null");
        this.vanillaEnchanter = Objects.requireNonNull(vanillaEnchanter, "vanillaEnchanter must not be null");
    }

    /**
     * The blueprint's own default enchantments as requests. Each uses the fetched full definition when
     * {@code fetchedDefinitions} has it (by definition id), else one built from the blueprint row's denormalized
     * fields ({@link EnchantmentDefinitionBukkitMapper#fromDefaultEnchantment}). Rows without a definition id are
     * left out. {@code fetchedDefinitions} may be null or empty.
     */
    public static List<EnchantmentRequest> defaultEnchantments(KnkItemBlueprint blueprint, Map<Integer, KnkEnchantmentDefinition> fetchedDefinitions) {
        if (blueprint == null || blueprint.defaultEnchantments() == null) {
            return Collections.emptyList();
        }

        List<EnchantmentRequest> requests = new ArrayList<>();
        for (KnkItemBlueprintDefaultEnchantment relation : blueprint.defaultEnchantments()) {
            if (relation == null || relation.enchantmentDefinitionId() == null) {
                continue;
            }

            KnkEnchantmentDefinition definition = fetchedDefinitions != null ? fetchedDefinitions.get(relation.enchantmentDefinitionId()) : null;
            if (definition == null) {
                definition = EnchantmentDefinitionBukkitMapper.fromDefaultEnchantment(relation);
            }
            requests.add(new EnchantmentRequest(relation.enchantmentDefinitionId(), definition, relation.level()));
        }
        return requests;
    }

    /**
     * Builds the item and applies {@code enchantments}. Throws (like {@link ItemBlueprintBukkitMapper#fromBlueprint})
     * when the blueprint is null or the material key is unknown.
     */
    public Result assemble(KnkItemBlueprint blueprint, String materialNamespaceKey, List<EnchantmentRequest> enchantments, Options options) {
        return enchant(build(blueprint, materialNamespaceKey), blueprint, enchantments, options);
    }

    /** {@link #assemble} with the blueprint's own default enchantments ({@link #defaultEnchantments}). */
    public Result assembleWithDefaults(
            KnkItemBlueprint blueprint,
            String materialNamespaceKey,
            Map<Integer, KnkEnchantmentDefinition> fetchedDefinitions,
            Options options
    ) {
        return assemble(blueprint, materialNamespaceKey, defaultEnchantments(blueprint, fetchedDefinitions), options);
    }

    /** Step 1 only: the plain blueprint item. Throws when the blueprint is null or the material key is unknown. */
    public ItemStack build(KnkItemBlueprint blueprint, String materialNamespaceKey) {
        return itemFactory.apply(blueprint, materialNamespaceKey);
    }

    /** Steps 2-4 on an item {@link #build} made from {@code blueprint}. Mutates and returns {@code itemStack}. */
    public Result enchant(ItemStack itemStack, KnkItemBlueprint blueprint, List<EnchantmentRequest> enchantments, Options options) {
        Objects.requireNonNull(itemStack, "itemStack must not be null");
        Options effective = options != null ? options : Options.DEFAULTS;

        int applied = 0;
        List<String> skipped = new ArrayList<>();

        // A permanent enchantment book (KNG-5) teaches its default enchantment; it must never carry it itself,
        // not even when the book couldn't be resolved.
        List<EnchantmentRequest> requests = enchantments == null || EnchantBookItems.isBookBlueprint(blueprint, itemStack.getType())
                ? Collections.emptyList()
                : enchantments;

        for (EnchantmentRequest request : requests) {
            if (request == null || request.definition() == null) {
                continue;
            }

            String skipPrefix = (request.definitionId() != null ? request.definitionId() : request.definition().id()) + " (";
            String skipReason = Boolean.TRUE.equals(request.definition().isCustom())
                    ? applyCustom(itemStack, request)
                    : applyVanilla(itemStack, request, effective.vanillaRules());
            if (skipReason == null) {
                applied++;
            } else {
                skipped.add(skipPrefix + skipReason + ")");
            }
        }

        reorderLoreEnchantmentsFirst(itemStack);

        if (effective.metaStamp() != null) {
            ItemMeta meta = itemStack.getItemMeta();
            if (meta != null) {
                effective.metaStamp().accept(meta);
                itemStack.setItemMeta(meta);
            }
        }

        return new Result(itemStack, applied, List.copyOf(skipped));
    }

    /** Null when applied, else why not. */
    private String applyCustom(ItemStack itemStack, EnchantmentRequest request) {
        EnchantmentDefinitionBukkitMapper.CustomEnchantmentResolution customResolution = EnchantmentDefinitionBukkitMapper.toCustom(request.definition());
        if (!customResolution.isValid()) {
            return customResolution.error();
        }

        int customLevel = request.level() != null && request.level() > 0 ? request.level() : customResolution.defaultLevel();
        if (customLevel > customResolution.maxLevel()) {
            return "level " + customLevel + " exceeds max " + customResolution.maxLevel();
        }

        return applyCustomLoreEnchantment(itemStack, customResolution.enchantmentId(), customLevel)
                ? null
                : "failed to apply custom lore enchantment";
    }

    /** Null when applied, else why not. */
    private String applyVanilla(ItemStack itemStack, EnchantmentRequest request, boolean vanillaRules) {
        VanillaEnchantment resolution = vanillaEnchanter.resolve(request.definition());
        if (!resolution.isValid()) {
            return resolution.error();
        }

        if (vanillaRules) {
            if (!vanillaEnchanter.canEnchantItem(resolution, itemStack)) {
                return resolution.namespaceKey() + " can't be applied to " + itemStack.getType();
            }
            if (vanillaEnchanter.conflictsWithItem(resolution, itemStack)) {
                return resolution.namespaceKey() + " conflicts with an enchantment already on the item";
            }
        }

        int level = request.level() != null && request.level() > 0 ? request.level() : resolution.defaultLevel();
        vanillaEnchanter.apply(resolution, itemStack, level);
        return null;
    }

    private boolean applyCustomLoreEnchantment(ItemStack itemStack, String enchantmentId, int level) {
        if (itemStack == null || itemStack.getType().isAir()) {
            return false;
        }

        ItemMeta itemMeta = itemStack.getItemMeta();
        List<String> lore = itemMeta != null && itemMeta.hasLore() ? itemMeta.getLore() : List.of();
        List<String> updatedLore = customEnchantmentRepository.applyEnchantment(lore, enchantmentId, level).join();

        if (itemMeta == null) {
            itemMeta = Bukkit.getItemFactory().getItemMeta(itemStack.getType());
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
        List<String> reorderedLore = CustomEnchantmentLore.enchantmentsFirst(customEnchantmentRepository, lore);
        if (reorderedLore.equals(lore)) {
            return;
        }

        if (itemMeta == null) {
            itemMeta = Bukkit.getItemFactory().getItemMeta(itemStack.getType());
        }

        if (itemMeta == null) {
            return;
        }

        itemMeta.setLore(reorderedLore);
        itemStack.setItemMeta(itemMeta);
    }
}
