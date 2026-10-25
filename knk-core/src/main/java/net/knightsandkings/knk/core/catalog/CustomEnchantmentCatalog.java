package net.knightsandkings.knk.core.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import net.knightsandkings.knk.core.domain.enchantment.Enchantment;
import net.knightsandkings.knk.core.domain.enchantment.EnchantmentRegistry;

/**
 * The custom enchantments of the catalogue snapshot (KNG-250, IMPLEMENTATION_PLAN.md §12.2), built from
 * knk-core's {@link EnchantmentRegistry}, and their merge with the server's registry enchantments.
 */
public final class CustomEnchantmentCatalog {

    /** The namespace of custom enchantment keys, e.g. {@code knk:flash_chaos}. */
    public static final String NAMESPACE = "knk";

    private CustomEnchantmentCatalog() {
    }

    /** Every enchantment of {@link EnchantmentRegistry#getInstance()} as a catalogue entry. */
    public static List<CatalogEnchantment> entries() {
        return entries(EnchantmentRegistry.getInstance().getAll());
    }

    /** {@code enchantments} as catalogue entries, in the given order. */
    public static List<CatalogEnchantment> entries(Collection<Enchantment> enchantments) {
        List<CatalogEnchantment> entries = new ArrayList<>(enchantments.size());
        for (Enchantment enchantment : enchantments) {
            entries.add(entryOf(enchantment));
        }
        return entries;
    }

    /** {@code knk:<lower-case id>}, custom, the registry's max level and name, the type as category. */
    public static CatalogEnchantment entryOf(Enchantment enchantment) {
        return new CatalogEnchantment(
            NAMESPACE + ":" + enchantment.id().toLowerCase(Locale.ROOT),
            true,
            enchantment.maxLevel(),
            null,
            enchantment.displayName(),
            enchantment.type() == null ? null : enchantment.type().name());
    }

    /**
     * The registry enchantments followed by the custom ones. A custom entry whose key is already taken
     * (by a registry entry or an earlier custom one) is left out and reported to {@code onSkipped}, so the
     * snapshot's keys stay unique.
     */
    public static List<CatalogEnchantment> merge(Collection<CatalogEnchantment> registryEnchantments,
                                                 Collection<CatalogEnchantment> customEnchantments,
                                                 Consumer<String> onSkipped) {
        List<CatalogEnchantment> merged = new ArrayList<>(registryEnchantments.size() + customEnchantments.size());
        Set<String> keys = new HashSet<>();
        for (CatalogEnchantment enchantment : registryEnchantments) {
            keys.add(enchantment.key());
            merged.add(enchantment);
        }
        for (CatalogEnchantment enchantment : customEnchantments) {
            if (keys.add(enchantment.key())) {
                merged.add(enchantment);
            } else {
                onSkipped.accept("Custom enchantment " + enchantment.key()
                    + " uses a key the catalogue already has (a server registry enchantment or an earlier"
                    + " custom one); it is left out of the snapshot.");
            }
        }
        return merged;
    }
}
