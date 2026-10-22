package net.knightsandkings.knk.paper.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.knightsandkings.knk.core.catalog.CatalogEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.Bukkit;
import org.bukkit.Material;

/**
 * {@link CatalogSource} over {@code Material.values()} (Paper 1.21.10, IMPLEMENTATION_PLAN.md §5.2a).
 * A value the server cannot give (translation key, display name, default block state) becomes null
 * instead of failing the scan.
 */
public final class BukkitMaterialCatalogSource implements CatalogSource {

    @Override
    public String minecraftVersion() {
        return Bukkit.getMinecraftVersion();
    }

    @Override
    public String serverVersion() {
        return Bukkit.getVersion();
    }

    @Override
    public List<Candidate> candidates() {
        List<Candidate> candidates = new ArrayList<>();
        for (Material material : Material.values()) {
            candidates.add(new MaterialCandidate(material));
        }
        return candidates;
    }

    private record MaterialCandidate(Material material) implements Candidate {
        @Override
        public boolean isLegacy() {
            return material.isLegacy();
        }

        @Override
        public boolean isAir() {
            return material.isAir();
        }

        @Override
        public CatalogEntry toEntry() {
            return entryOf(material);
        }
    }

    static CatalogEntry entryOf(Material material) {
        boolean isItem = material.isItem();
        boolean isBlock = material.isBlock();
        String translationKey = translationKey(material, isItem, isBlock);
        return new CatalogEntry(material.getKey().toString(), isItem, isBlock, material.getMaxStackSize(),
            translationKey, displayName(translationKey), isBlock ? defaultBlockState(material) : null);
    }

    /** The item key for items (a block item has its block's key), the block key for block-only entries. */
    private static String translationKey(Material material, boolean isItem, boolean isBlock) {
        try {
            if (isItem) {
                return material.getItemTranslationKey();
            }
            return isBlock ? material.getBlockTranslationKey() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code translationKey} rendered to en_US, or null when the server has no translation for it. */
    static String displayName(String translationKey) {
        if (translationKey == null) {
            return null;
        }
        try {
            String rendered = PlainTextComponentSerializer.plainText()
                .serialize(GlobalTranslator.render(Component.translatable(translationKey), Locale.US));
            return rendered.isEmpty() || rendered.equals(translationKey) ? null : rendered;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String defaultBlockState(Material material) {
        try {
            return material.createBlockData().getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
