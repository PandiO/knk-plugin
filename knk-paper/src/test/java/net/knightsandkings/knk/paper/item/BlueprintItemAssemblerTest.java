package net.knightsandkings.knk.paper.item;

import net.knightsandkings.knk.api.impl.enchantment.LocalEnchantmentRepositoryImpl;
import net.knightsandkings.knk.core.domain.enchantments.KnkEnchantmentDefinition;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprintDefaultEnchantment;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lootboxes Phase 0: the enchantment step extracted from {@code /knk itemblueprints give}. Items and the Bukkit
 * enchantment registry are faked (a real ItemStack / Enchantment needs a server); custom enchantments run through
 * the real {@link LocalEnchantmentRepositoryImpl} and core registry.
 */
class BlueprintItemAssemblerTest {

    private static final KnkEnchantmentDefinition SHARPNESS = vanilla(1, "minecraft:sharpness", 5);
    private static final KnkEnchantmentDefinition SMITE = vanilla(2, "minecraft:smite", 5);
    private static final KnkEnchantmentDefinition MENDING = vanilla(3, "minecraft:mending", 1);
    private static final KnkEnchantmentDefinition POISON = custom(10, "knk:poison", "Poison", 3);

    private final FakeVanillaEnchanter vanilla = new FakeVanillaEnchanter();
    private final Map<ItemStack, List<String>> loreByItem = new IdentityHashMap<>();

    @Test
    void appliesVanillaAndCustomEnchantments() {
        ItemStack sword = item(Material.DIAMOND_SWORD, "§7A fine blade", "§l§bGrade: ★★");

        BlueprintItemAssembler.Result result = assembler(sword).assemble(blueprint(), "minecraft:diamond_sword",
                List.of(request(SHARPNESS, 4), request(POISON, 2)), BlueprintItemAssembler.Options.DEFAULTS);

        assertSame(sword, result.itemStack());
        assertEquals(2, result.applied());
        assertTrue(result.skipped().isEmpty());
        assertEquals(Map.of("minecraft:sharpness", 4), vanilla.appliedOn(sword));
        // Custom enchantment lines go first, above the description and grade line.
        assertEquals(List.of("§7Poison II", "§7A fine blade", "§l§bGrade: ★★"), loreByItem.get(sword));
    }

    @Test
    void levelZeroOrMissingUsesTheDefaultLevel() {
        ItemStack sword = item(Material.DIAMOND_SWORD);

        assembler(sword).assemble(blueprint(), "minecraft:diamond_sword",
                List.of(request(SHARPNESS, null), request(POISON, 0)), BlueprintItemAssembler.Options.DEFAULTS);

        assertEquals(Map.of("minecraft:sharpness", 5), vanilla.appliedOn(sword));
        assertEquals(List.of("§7Poison III"), loreByItem.get(sword));
    }

    @Test
    void customLevelAboveItsMaxIsSkipped() {
        ItemStack sword = item(Material.DIAMOND_SWORD);

        BlueprintItemAssembler.Result result = assembler(sword).assemble(blueprint(), "minecraft:diamond_sword",
                List.of(request(POISON, 5), request(SHARPNESS, 2)), BlueprintItemAssembler.Options.DEFAULTS);

        assertEquals(1, result.applied());
        assertEquals(List.of("10 (level 5 exceeds max 3)"), result.skipped());
        assertEquals(List.of(), loreByItem.get(sword));
    }

    @Test
    void unresolvableEnchantmentIsSkippedWithItsReason() {
        ItemStack sword = item(Material.DIAMOND_SWORD);
        KnkEnchantmentDefinition unknownCustom = custom(11, "knk:nope", "Nope", 3);

        BlueprintItemAssembler.Result result = assembler(sword).assemble(blueprint(), "minecraft:diamond_sword",
                List.of(request(vanilla(12, "minecraft:not_real", 1), 1), request(unknownCustom, 1)),
                BlueprintItemAssembler.Options.DEFAULTS);

        assertEquals(0, result.applied());
        assertEquals(List.of("12 (not registered: minecraft:not_real)",
                "11 (Unable to resolve custom enchantment id from key/displayName)"), result.skipped());
    }

    @Test
    void enchantmentBookBlueprintGetsNoEnchantments() {
        ItemStack book = item(Material.ENCHANTED_BOOK);
        KnkItemBlueprint bookBlueprint = blueprint(new KnkItemBlueprintDefaultEnchantment(7, 1, 3, "minecraft:sharpness", "Sharpness", 5, false));

        BlueprintItemAssembler.Result result = assembler(book).assemble(bookBlueprint, "minecraft:enchanted_book",
                List.of(request(SHARPNESS, 3), request(POISON, 1)), BlueprintItemAssembler.Options.DEFAULTS);

        assertEquals(0, result.applied());
        assertTrue(result.skipped().isEmpty());
        assertTrue(vanilla.appliedOn(book).isEmpty());
        assertEquals(List.of(), loreByItem.get(book));
    }

    @Test
    void conflictingPairIsSkippedWhenVanillaRulesAreOn() {
        ItemStack sword = item(Material.DIAMOND_SWORD);
        vanilla.conflicts("minecraft:sharpness", "minecraft:smite");

        BlueprintItemAssembler.Result result = assembler(sword).assemble(blueprint(), "minecraft:diamond_sword",
                List.of(request(SHARPNESS, 3), request(SMITE, 3)), BlueprintItemAssembler.Options.DEFAULTS.withVanillaRules(true));

        assertEquals(1, result.applied());
        assertEquals(List.of("2 (minecraft:smite conflicts with an enchantment already on the item)"), result.skipped());
        assertEquals(Map.of("minecraft:sharpness", 3), vanilla.appliedOn(sword));
    }

    @Test
    void enchantmentTheItemCantTakeIsSkippedWhenVanillaRulesAreOn() {
        ItemStack sword = item(Material.DIAMOND_SWORD);
        vanilla.cannotEnchant("minecraft:mending", Material.DIAMOND_SWORD);

        BlueprintItemAssembler.Result result = assembler(sword).assemble(blueprint(), "minecraft:diamond_sword",
                List.of(request(MENDING, 1)), BlueprintItemAssembler.Options.DEFAULTS.withVanillaRules(true));

        assertEquals(0, result.applied());
        assertEquals(List.of("3 (minecraft:mending can't be applied to DIAMOND_SWORD)"), result.skipped());
    }

    @Test
    void blueprintDefaultsIgnoreVanillaRulesAsTheGiveCommandAlwaysHas() {
        ItemStack sword = item(Material.DIAMOND_SWORD);
        vanilla.conflicts("minecraft:sharpness", "minecraft:smite");
        vanilla.cannotEnchant("minecraft:mending", Material.DIAMOND_SWORD);

        BlueprintItemAssembler.Result result = assembler(sword).assemble(blueprint(), "minecraft:diamond_sword",
                List.of(request(SHARPNESS, 3), request(SMITE, 3), request(MENDING, 1)), BlueprintItemAssembler.Options.DEFAULTS);

        assertEquals(3, result.applied());
        assertEquals(Set.of("minecraft:sharpness", "minecraft:smite", "minecraft:mending"), vanilla.appliedOn(sword).keySet());
    }

    @Test
    void metaStampRunsLastOnTheItemsMeta() {
        ItemStack sword = item(Material.DIAMOND_SWORD);
        List<ItemMeta> stamped = new ArrayList<>();

        assembler(sword).assemble(blueprint(), "minecraft:diamond_sword", List.of(request(POISON, 1)),
                BlueprintItemAssembler.Options.DEFAULTS.withMetaStamp(meta -> {
                    stamped.add(meta);
                    // The custom enchantment lore is already in place when the stamp runs.
                    assertEquals(List.of("§7Poison I"), meta.getLore());
                }));

        assertEquals(1, stamped.size());
        assertSame(sword.getItemMeta(), stamped.get(0));
        verify(sword, atLeastOnce()).setItemMeta(stamped.get(0));
    }

    @Test
    void defaultEnchantmentsPreferFetchedDefinitionsAndFallBackToTheBlueprintRow() {
        KnkEnchantmentDefinition fetched = new KnkEnchantmentDefinition(1, "sharp", "Sharpness", null, false, 5, null, "minecraft:sharpness");
        KnkItemBlueprint blueprint = blueprint(
                new KnkItemBlueprintDefaultEnchantment(7, 1, 4, "minecraft:sharpness", "Sharpness", 5, false),
                new KnkItemBlueprintDefaultEnchantment(7, 10, 2, "knk:poison", "Poison", 3, true),
                new KnkItemBlueprintDefaultEnchantment(7, null, 1, "minecraft:unbreaking", "Unbreaking", 3, false),
                null);

        List<BlueprintItemAssembler.EnchantmentRequest> requests = BlueprintItemAssembler.defaultEnchantments(blueprint, Map.of(1, fetched));

        assertEquals(2, requests.size());
        assertSame(fetched, requests.get(0).definition());
        assertEquals(Integer.valueOf(4), requests.get(0).level());
        assertEquals(Integer.valueOf(10), requests.get(1).definitionId());
        assertEquals("knk:poison", requests.get(1).definition().key());
        assertEquals(Boolean.TRUE, requests.get(1).definition().isCustom());
        assertEquals(Integer.valueOf(2), requests.get(1).level());
    }

    @Test
    void assembleWithDefaultsAppliesTheBlueprintsOwnEnchantments() {
        ItemStack sword = item(Material.DIAMOND_SWORD);
        KnkItemBlueprint blueprint = blueprint(
                new KnkItemBlueprintDefaultEnchantment(7, 1, 4, "minecraft:sharpness", "Sharpness", 5, false),
                new KnkItemBlueprintDefaultEnchantment(7, 10, 2, "knk:poison", "Poison", 3, true));

        BlueprintItemAssembler.Result result = assembler(sword).assembleWithDefaults(blueprint, "minecraft:diamond_sword",
                null, BlueprintItemAssembler.Options.DEFAULTS);

        assertEquals(2, result.applied());
        assertEquals(Map.of("minecraft:sharpness", 4), vanilla.appliedOn(sword));
        assertEquals(List.of("§7Poison II"), loreByItem.get(sword));
    }

    // --- fixtures ---

    private BlueprintItemAssembler assembler(ItemStack built) {
        return new BlueprintItemAssembler(new LocalEnchantmentRepositoryImpl(), (blueprint, key) -> built, vanilla);
    }

    /** A stand-in ItemStack whose meta keeps its lore, like a real one would. */
    private ItemStack item(Material material, String... lore) {
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        loreByItem.put(stack, new ArrayList<>(List.of(lore)));
        when(stack.getType()).thenReturn(material);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.hasLore()).thenAnswer(invocation -> !loreByItem.get(stack).isEmpty());
        when(meta.getLore()).thenAnswer(invocation -> new ArrayList<>(loreByItem.get(stack)));
        doAnswer(invocation -> {
            loreByItem.put(stack, new ArrayList<>(invocation.<List<String>>getArgument(0)));
            return null;
        }).when(meta).setLore(anyList());
        return stack;
    }

    private static KnkItemBlueprint blueprint(KnkItemBlueprintDefaultEnchantment... defaults) {
        List<KnkItemBlueprintDefaultEnchantment> list = new ArrayList<>();
        for (KnkItemBlueprintDefaultEnchantment relation : defaults) {
            list.add(relation);
        }
        return new KnkItemBlueprint(7, "test_item", null, null, null, "Test", null, 1, 1,
                list, list.size(), null, List.of(), List.of());
    }

    private static BlueprintItemAssembler.EnchantmentRequest request(KnkEnchantmentDefinition definition, Integer level) {
        return new BlueprintItemAssembler.EnchantmentRequest(definition.id(), definition, level);
    }

    private static KnkEnchantmentDefinition vanilla(int id, String key, int maxLevel) {
        return new KnkEnchantmentDefinition(id, key, key, null, false, maxLevel, null, key);
    }

    private static KnkEnchantmentDefinition custom(int id, String key, String displayName, int maxLevel) {
        return new KnkEnchantmentDefinition(id, key, displayName, null, true, maxLevel, null, null);
    }

    /** The Bukkit registry side, by namespace key: every {@code minecraft:*} key except "not_real" exists. */
    private static final class FakeVanillaEnchanter implements BlueprintItemAssembler.VanillaEnchanter {
        private final Map<ItemStack, Map<String, Integer>> applied = new IdentityHashMap<>();
        private final Map<String, Set<String>> conflicts = new HashMap<>();
        private final Map<String, Material> cannotEnchant = new HashMap<>();

        void conflicts(String a, String b) {
            conflicts.computeIfAbsent(a, k -> new HashSet<>()).add(b);
            conflicts.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        }

        void cannotEnchant(String key, Material material) {
            cannotEnchant.put(key, material);
        }

        Map<String, Integer> appliedOn(ItemStack stack) {
            return applied.getOrDefault(stack, Map.of());
        }

        @Override
        public BlueprintItemAssembler.VanillaEnchantment resolve(KnkEnchantmentDefinition definition) {
            String key = definition.baseEnchantmentNamespaceKey();
            if (key == null || key.endsWith("not_real")) {
                return BlueprintItemAssembler.VanillaEnchantment.invalid("not registered: " + key);
            }
            return new BlueprintItemAssembler.VanillaEnchantment(key, definition.maxLevel(), null, null);
        }

        @Override
        public boolean canEnchantItem(BlueprintItemAssembler.VanillaEnchantment enchantment, ItemStack itemStack) {
            return cannotEnchant.get(enchantment.namespaceKey()) != itemStack.getType();
        }

        @Override
        public boolean conflictsWithItem(BlueprintItemAssembler.VanillaEnchantment enchantment, ItemStack itemStack) {
            Set<String> conflicting = conflicts.getOrDefault(enchantment.namespaceKey(), Set.of());
            return appliedOn(itemStack).keySet().stream().anyMatch(conflicting::contains);
        }

        @Override
        public void apply(BlueprintItemAssembler.VanillaEnchantment enchantment, ItemStack itemStack, int level) {
            applied.computeIfAbsent(itemStack, k -> new LinkedHashMap<>()).put(enchantment.namespaceKey(), level);
        }
    }
}
