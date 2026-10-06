package net.knightsandkings.knk.core.domain.enchantment;

import net.knightsandkings.knk.core.domain.item.ItemLoreLayout;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one way to put a custom (lore-based) enchantment on an item, or take one off: {@link EnchantmentRepository#applyEnchantment}
 * (or {@code removeEnchantment}) followed by {@link #enchantmentsFirst}, so the custom enchantment lines sit at the
 * top of the lore, directly under the client's vanilla enchantment list, and everything else (description, then the
 * {@code Grade:} and {@code Origin:} lines at the bottom) keeps its order below them, with the spacing of
 * {@link net.knightsandkings.knk.core.domain.item.ItemLoreLayout} (removing the last enchantment leaves no stray blank
 * line).
 * <p>
 * {@code /ce add}, {@code /knk itemblueprints give}, {@code /knk enchantments apply} and the permanent
 * enchantment books all go through here. The first three used to carry their own copy of the reorder step; the
 * books skipped it, so a book-applied enchantment ended up under the grade line.
 */
public final class CustomEnchantmentLore {

    private CustomEnchantmentLore() {
    }

    /** Applies (adds or replaces) {@code enchantmentId} at {@code level}, then moves all custom enchantment lines first. */
    public static List<String> apply(EnchantmentRepository repository, List<String> lore, String enchantmentId, int level) {
        List<String> applied = repository.applyEnchantment(lore == null ? List.of() : lore, enchantmentId, level).join();
        return enchantmentsFirst(repository, applied);
    }

    /** Removes {@code enchantmentId}, then re-composes the lore so the spacer under the enchantment block goes with it. */
    public static List<String> remove(EnchantmentRepository repository, List<String> lore, String enchantmentId) {
        List<String> removed = repository.removeEnchantment(lore == null ? List.of() : lore, enchantmentId).join();
        return enchantmentsFirst(repository, removed);
    }

    /**
     * The same lines, custom enchantment lines first (in their existing order), with the shared
     * item-lore spacing applied to the remaining description/grade/origin sections.
     */
    public static List<String> enchantmentsFirst(EnchantmentRepository repository, List<String> lore) {
        if (lore == null || lore.isEmpty()) return List.of();

        Map<String, Integer> enchantments = repository.getEnchantments(lore).join();

        List<String> rest = new ArrayList<>(lore);
        for (String enchantmentId : enchantments.keySet()) {
            rest = repository.removeEnchantment(rest, enchantmentId).join();
        }

        List<String> enchantmentLines = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : enchantments.entrySet()) {
            int level = entry.getValue() != null && entry.getValue() > 0 ? entry.getValue() : 1;
            enchantmentLines = repository.applyEnchantment(enchantmentLines, entry.getKey(), level).join();
        }

        return ItemLoreLayout.compose(enchantmentLines, rest);
    }
}
