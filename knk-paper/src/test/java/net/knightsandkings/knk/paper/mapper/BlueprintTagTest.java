package net.knightsandkings.knk.paper.mapper;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PandiO/knk-workspace#141: the knk_blueprint tag, off by default, never on a plain item. */
class BlueprintTagTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        BlueprintTag.setStampingEnabled(false);
        MockBukkit.unmock();
    }

    @SuppressWarnings("deprecation")
    private static ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    @Test
    void stampingIsOffByDefault_itemsAreBuiltAsBefore() {
        ItemStack bread = named(Material.BREAD, "Phoenix Bread");
        assertFalse(BlueprintTag.stampingEnabled());
        assertFalse(BlueprintTag.stampIfEnabled(bread, 20));
        assertEquals(Optional.empty(), BlueprintTag.read(bread));
    }

    @Test
    void whenOn_aCustomisedItemIsTagged_aPlainItemIsNot() {
        BlueprintTag.setStampingEnabled(true);
        ItemStack bread = named(Material.BREAD, "Phoenix Bread");
        ItemStack plain = new ItemStack(Material.OAK_LOG, 16);

        assertTrue(BlueprintTag.stampIfEnabled(bread, 20));
        assertFalse(BlueprintTag.stampIfEnabled(plain, 10));

        assertEquals(Optional.of(20), BlueprintTag.read(bread));
        assertTrue(plain.isSimilar(new ItemStack(Material.OAK_LOG)));
    }

    @Test
    void aPlainItemStillStacksWithVanilla_andATaggedPlainOneDoesOnceNormalised() {
        BlueprintTag.setStampingEnabled(true);
        ItemStack vanilla = new ItemStack(Material.OAK_LOG, 3);
        ItemStack built = new ItemStack(Material.OAK_LOG, 5);
        BlueprintTag.stampIfEnabled(built, 10);
        assertTrue(built.isSimilar(vanilla));

        ItemStack stale = new ItemStack(Material.OAK_LOG, 2);
        ItemMeta meta = stale.getItemMeta();
        meta.getPersistentDataContainer().set(BlueprintTag.BLUEPRINT_KEY, org.bukkit.persistence.PersistentDataType.INTEGER, 10);
        stale.setItemMeta(meta);
        assertFalse(stale.isSimilar(vanilla));
        assertTrue(BlueprintTag.isPlainWithoutBlueprintTag(stale));

        assertTrue(BlueprintTag.remove(stale));
        assertTrue(stale.isSimilar(vanilla));
        assertFalse(BlueprintTag.remove(stale));
    }

    @Test
    void aNamedItemIsNotPlain() {
        assertFalse(BlueprintTag.isPlainWithoutBlueprintTag(named(Material.BREAD, "x")));
        assertTrue(BlueprintTag.isPlainWithoutBlueprintTag(new ItemStack(Material.BREAD)));
    }
}
