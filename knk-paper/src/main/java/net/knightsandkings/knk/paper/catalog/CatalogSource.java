package net.knightsandkings.knk.paper.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import net.knightsandkings.knk.core.catalog.CatalogEnchantment;
import net.knightsandkings.knk.core.catalog.CatalogEntry;
import net.knightsandkings.knk.core.catalog.CustomEnchantmentCatalog;

/**
 * Where the Minecraft catalogue scan (KNG-200, KNG-250) reads the server's materials and enchantments
 * from ({@link BukkitCatalogSource} on Paper), so the scan handler can be tested without a server.
 * Read on the main thread.
 */
public interface CatalogSource {

    /** One material the server lists, legacy and air included; {@link #toEntry()} is only called on kept ones. */
    interface Candidate {
        boolean isLegacy();

        boolean isAir();

        CatalogEntry toEntry();
    }

    /** {@code Bukkit.getMinecraftVersion()}, e.g. {@code 26.3}. */
    String minecraftVersion();

    /** {@code Bukkit.getVersion()}. */
    String serverVersion();

    List<Candidate> candidates();

    /** The enchantments of the server's registry (vanilla and data-pack ones), {@code isCustom} false. */
    List<CatalogEnchantment> registryEnchantments();

    /** The custom enchantments of knk-core's {@code EnchantmentRegistry} (IMPLEMENTATION_PLAN.md §12.2). */
    default List<CatalogEnchantment> customEnchantments() {
        return CustomEnchantmentCatalog.entries();
    }

    /** The catalogue: every candidate except legacy materials and air. */
    default List<CatalogEntry> entries() {
        List<CatalogEntry> entries = new ArrayList<>();
        for (Candidate candidate : candidates()) {
            if (!candidate.isLegacy() && !candidate.isAir()) {
                entries.add(candidate.toEntry());
            }
        }
        return entries;
    }

    /**
     * The registry enchantments plus the custom ones. A custom enchantment whose key the registry already
     * has is left out with a warning, so the snapshot's keys stay unique.
     */
    default List<CatalogEnchantment> enchantments() {
        return CustomEnchantmentCatalog.merge(registryEnchantments(), customEnchantments(),
            message -> Logger.getLogger(CatalogSource.class.getName()).warning("[MinecraftCatalogScan] " + message));
    }
}
