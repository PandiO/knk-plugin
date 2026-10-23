package net.knightsandkings.knk.paper.catalog;

import net.knightsandkings.knk.core.catalog.CatalogEnchantment;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationStore;
import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * KNG-200/KNG-250: display names come from adventure's GlobalTranslator (en_US), and a key without a
 * translation gives null rather than the key itself (IMPLEMENTATION_PLAN.md §5.2a, §12.2). A registry
 * enchantment's translation key is the key of its translatable description.
 */
class BukkitCatalogSourceTest {

    @Test
    void aKeyWithoutTranslation_hasNoDisplayName() {
        assertNull(BukkitCatalogSource.displayName("block.knk_test.untranslated"));
        assertNull(BukkitCatalogSource.displayName((String) null));
        assertNull(BukkitCatalogSource.displayName((Component) null));
        assertNull(BukkitCatalogSource.displayName(Component.text("  ")));
    }

    @Test
    void aTranslatedKey_rendersToEnUs() {
        TranslationStore.StringBased<MessageFormat> store = TranslationStore.messageFormat(Key.key("knk", "catalog_test"));
        store.register("block.knk_test.stone", Locale.US, new MessageFormat("Stone", Locale.US));
        GlobalTranslator.translator().addSource(store);
        try {
            assertEquals("Stone", BukkitCatalogSource.displayName("block.knk_test.stone"));
        } finally {
            GlobalTranslator.translator().removeSource(store);
        }
    }

    @Test
    void aVanillaEnchantment_hasItsDescriptionKey_andTheRenderedName() {
        TranslationStore.StringBased<MessageFormat> store = TranslationStore.messageFormat(Key.key("knk", "catalog_enchantment_test"));
        store.register("enchantment.knk_test.sharpness", Locale.US, new MessageFormat("Sharpness", Locale.US));
        GlobalTranslator.translator().addSource(store);
        try {
            assertEquals(new CatalogEnchantment("minecraft:sharpness", false, 5, "enchantment.knk_test.sharpness", "Sharpness", null),
                BukkitCatalogSource.enchantmentEntryOf("minecraft:sharpness", 5,
                    Component.translatable("enchantment.knk_test.sharpness")));
        } finally {
            GlobalTranslator.translator().removeSource(store);
        }
    }

    @Test
    void anUntranslatedEnchantment_keepsItsKey_withoutDisplayName() {
        assertEquals(new CatalogEnchantment("minecraft:new_one", false, 3, "enchantment.knk_test.untranslated", null, null),
            BukkitCatalogSource.enchantmentEntryOf("minecraft:new_one", 3,
                Component.translatable("enchantment.knk_test.untranslated")));
    }

    @Test
    void aDataPackEnchantmentWithPlainTextDescription_hasNoTranslationKey() {
        assertEquals(new CatalogEnchantment("mypack:lifesteal", false, 2, null, "Lifesteal", null),
            BukkitCatalogSource.enchantmentEntryOf("mypack:lifesteal", 2, Component.text("Lifesteal")));
        assertEquals(new CatalogEnchantment("mypack:broken", false, 1, null, null, null),
            BukkitCatalogSource.enchantmentEntryOf("mypack:broken", 1, null));
    }
}
