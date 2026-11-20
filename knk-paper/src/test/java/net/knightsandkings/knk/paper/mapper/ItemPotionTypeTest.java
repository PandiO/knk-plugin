package net.knightsandkings.knk.paper.mapper;

import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Siege shop (DESIGN § 9.4; PandiO/knk-workspace#867, #868): a blueprint's vanilla potion type reaches the item, so the
 * shop's "Splash Potion of Healing II" is one. MockBukkit for the potion registry.
 */
class ItemPotionTypeTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static KnkItemBlueprint blueprint(String potionType) {
        return new KnkItemBlueprint(7, "Splash Potion of Healing II", null, null, "minecraft:splash_potion", null, null,
                1, null, List.of(), 0, null, List.of(), List.of(), null, null, potionType);
    }

    @Test
    void theBlueprintsPotionTypeIsOnTheBuiltItem() {
        ItemStack item = ItemBlueprintBukkitMapper.fromBlueprint(blueprint("minecraft:strong_healing"), "minecraft:splash_potion");
        assertEquals(Material.SPLASH_POTION, item.getType());
        assertEquals(PotionType.STRONG_HEALING, ((PotionMeta) item.getItemMeta()).getBasePotionType());
    }

    @Test
    void anUnknownKeyIsWarnedAndSkipped_andOtherMetasAreLeftAlone() {
        PotionMeta potion = (PotionMeta) new ItemStack(Material.POTION).getItemMeta();
        List<String> warnings = new ArrayList<>();
        ItemAppearanceBukkitMapper.applyPotionType(potion, "minecraft:healing_iii", warnings::add);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("healing_iii"));

        ItemAppearanceBukkitMapper.applyPotionType(potion, "minecraft:regeneration", warnings::add);
        assertEquals(PotionType.REGENERATION, potion.getBasePotionType());

        ItemMeta sword = mock(ItemMeta.class);
        ItemAppearanceBukkitMapper.applyPotionType(sword, "minecraft:regeneration", warnings::add);
        ItemAppearanceBukkitMapper.applyPotionType(potion, null, warnings::add);
        verifyNoInteractions(sword);
        assertEquals(1, warnings.size());
    }
}
