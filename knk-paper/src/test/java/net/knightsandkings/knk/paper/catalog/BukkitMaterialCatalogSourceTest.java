package net.knightsandkings.knk.paper.catalog;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.translation.GlobalTranslator;
import net.kyori.adventure.translation.TranslationStore;
import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * KNG-200: display names come from adventure's GlobalTranslator (en_US), and a key without a
 * translation gives null rather than the key itself (IMPLEMENTATION_PLAN.md §5.2a).
 */
class BukkitMaterialCatalogSourceTest {

    @Test
    void aKeyWithoutTranslation_hasNoDisplayName() {
        assertNull(BukkitMaterialCatalogSource.displayName("block.knk_test.untranslated"));
        assertNull(BukkitMaterialCatalogSource.displayName(null));
    }

    @Test
    void aTranslatedKey_rendersToEnUs() {
        TranslationStore.StringBased<MessageFormat> store = TranslationStore.messageFormat(Key.key("knk", "catalog_test"));
        store.register("block.knk_test.stone", Locale.US, new MessageFormat("Stone", Locale.US));
        GlobalTranslator.translator().addSource(store);
        try {
            assertEquals("Stone", BukkitMaterialCatalogSource.displayName("block.knk_test.stone"));
        } finally {
            GlobalTranslator.translator().removeSource(store);
        }
    }
}
