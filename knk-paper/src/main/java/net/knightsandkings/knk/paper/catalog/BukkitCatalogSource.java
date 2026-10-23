package net.knightsandkings.knk.paper.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.knightsandkings.knk.core.catalog.CatalogEnchantment;
import net.knightsandkings.knk.core.catalog.CatalogEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;

/**
 * {@link CatalogSource} over the running Paper 26.3 server: the materials of {@code Material.values()}
 * (IMPLEMENTATION_PLAN.md §5.2a) and the enchantments of Paper's enchantment registry (§12.2).
 * A value the server cannot give (translation key, display name, default block state) becomes null
 * instead of failing the scan.
 */
public final class BukkitCatalogSource implements CatalogSource {

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

    /** Every enchantment of Paper's enchantment registry: vanilla and data-pack ones. */
    @Override
    public List<CatalogEnchantment> registryEnchantments() {
        Registry<Enchantment> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
        List<CatalogEnchantment> enchantments = new ArrayList<>();
        for (Enchantment enchantment : registry) {
            enchantments.add(enchantmentEntryOf(registry.getKeyOrThrow(enchantment).toString(),
                enchantment.getMaxLevel(), description(enchantment)));
        }
        return enchantments;
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

    /**
     * A registry enchantment. The translation key is the key of its translatable description (what Paper's
     * {@code Enchantment.translationKey()}, deprecated for removal, returns); null for a data-pack enchantment
     * whose description is plain text. The display name is the description rendered to en_US.
     */
    static CatalogEnchantment enchantmentEntryOf(String key, int maxLevel, Component description) {
        String translationKey = description instanceof TranslatableComponent translatable ? translatable.key() : null;
        return new CatalogEnchantment(key, false, maxLevel, translationKey, displayName(description), null);
    }

    private static Component description(Enchantment enchantment) {
        try {
            return enchantment.description();
        } catch (RuntimeException e) {
            return null;
        }
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
        return translationKey == null ? null : displayName(Component.translatable(translationKey));
    }

    /**
     * {@code component} rendered to en_US as plain text, or null when that is blank or, for a translatable
     * component, just its key (the server has no translation for it).
     */
    static String displayName(Component component) {
        if (component == null) {
            return null;
        }
        try {
            String rendered = PlainTextComponentSerializer.plainText()
                .serialize(GlobalTranslator.render(component, Locale.US));
            if (rendered.isBlank()) {
                return null;
            }
            return component instanceof TranslatableComponent translatable && rendered.equals(translatable.key())
                ? null : rendered;
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
