package net.knightsandkings.knk.paper.catalog;

import java.util.ArrayList;
import java.util.List;

import net.knightsandkings.knk.core.catalog.CatalogEntry;

/**
 * Where the Minecraft catalogue scan (KNG-200) reads the server's materials from. Paper 1.21.10 reads
 * {@code Material.values()} ({@link BukkitMaterialCatalogSource}); a later Paper version can swap in a
 * registry-based reader without touching the scan handler. Read on the main thread.
 */
public interface CatalogSource {

    /** One material the server lists, legacy and air included; {@link #toEntry()} is only called on kept ones. */
    interface Candidate {
        boolean isLegacy();

        boolean isAir();

        CatalogEntry toEntry();
    }

    /** {@code Bukkit.getMinecraftVersion()}, e.g. {@code 1.21.10}. */
    String minecraftVersion();

    /** {@code Bukkit.getVersion()}. */
    String serverVersion();

    List<Candidate> candidates();

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
}
