package net.knightsandkings.knk.paper.item;

import net.knightsandkings.knk.api.impl.enchantment.LocalEnchantmentRepositoryImpl;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.paper.mapper.BlueprintTag;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PandiO/knk-workspace#141: the assembler (kits, lootboxes, {@code /knk itemblueprints give}) stamps knk_blueprint only
 * with item identity on and only on items that differ from vanilla; a shadow blueprint builds the plain vanilla item.
 */
class BlueprintItemAssemblerIdentityTest {

    private final BlueprintItemAssembler assembler = new BlueprintItemAssembler(new LocalEnchantmentRepositoryImpl());

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        BlueprintTag.setStampingEnabled(false);
        MockBukkit.unmock();
    }

    private static KnkItemBlueprint blueprint(int id, String key, String displayName) {
        return new KnkItemBlueprint(id, "Bp " + id, null, null, key, displayName, null, 1, 64, List.of(), 0, null, List.of(), List.of());
    }

    @Test
    void identityOff_kitItemsAreBuiltAsBefore_withoutATag() {
        ItemStack bread = assembler.assembleDefaults(blueprint(20, "minecraft:bread", "&6Phoenix Bread"), "minecraft:bread", Map.of(), 16).itemStack();
        assertEquals(Optional.empty(), BlueprintTag.read(bread));
        assertEquals(16, bread.getAmount());
    }

    @Test
    void identityOn_aCustomBlueprintItemIsTagged() {
        BlueprintTag.setStampingEnabled(true);
        ItemStack bread = assembler.assembleDefaults(blueprint(20, "minecraft:bread", "&6Phoenix Bread"), "minecraft:bread", Map.of(), 16).itemStack();
        assertEquals(Optional.of(20), BlueprintTag.read(bread));
        assertEquals(16, bread.getAmount());
    }

    @Test
    void identityOn_aShadowBlueprintBuildsThePlainVanillaItem_whichStacksWithVanilla() {
        BlueprintTag.setStampingEnabled(true);
        ItemStack log = assembler.assembleDefaults(blueprint(10, "minecraft:oak_log", ""), "minecraft:oak_log", Map.of(), 32).itemStack();
        assertEquals(Optional.empty(), BlueprintTag.read(log));
        assertTrue(log.isSimilar(new ItemStack(Material.OAK_LOG)));
    }
}
