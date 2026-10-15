package net.knightsandkings.knk.paper.menu.content;

import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprintDefaultEnchantment;
import net.knightsandkings.knk.paper.item.BlueprintItemAssembler;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** KNG-175: a catalogue row is the item a player gets, default enchantments included. */
class CatalogItemRowTest {

    private final BlueprintItemAssembler assembler = mock(BlueprintItemAssembler.class);

    private static KnkItemBlueprint swordWithSharpness() {
        return new KnkItemBlueprint(7, "sword", null, 5, "minecraft:iron_sword", "Sword", null, 1, 1,
                List.of(new KnkItemBlueprintDefaultEnchantment(7, 1, 4, "minecraft:sharpness", "Sharpness", 5, false)), 1,
                null, List.of(), List.of());
    }

    @Test
    void rowIsBuiltWithTheBlueprintsDefaultEnchantments_once() {
        KnkItemBlueprint blueprint = swordWithSharpness();
        ItemStack item = mock(ItemStack.class);
        when(assembler.assembleDefaults(blueprint, "minecraft:iron_sword", Map.of(), null))
                .thenReturn(new BlueprintItemAssembler.Result(item, 1, List.of()));
        CatalogItemRow row = new CatalogItemRow(blueprint, "minecraft:iron_sword", assembler);

        assertSame(item, row.menuItemStack());
        assertSame(item, row.menuItemStack());
        verify(assembler, times(1)).assembleDefaults(blueprint, "minecraft:iron_sword", Map.of(), null);
    }

    @Test
    void skippedEnchantmentsStillGiveTheItem() {
        KnkItemBlueprint blueprint = swordWithSharpness();
        ItemStack item = mock(ItemStack.class);
        when(assembler.assembleDefaults(any(), anyString(), anyMap(), any()))
                .thenReturn(new BlueprintItemAssembler.Result(item, 0, List.of("1 (not registered: minecraft:sharpness)")));

        assertSame(item, new CatalogItemRow(blueprint, "minecraft:iron_sword", assembler).menuItemStack());
    }

    @Test
    void unknownOrMissingMaterialFallsBackToTheNamedRow() {
        KnkItemBlueprint blueprint = swordWithSharpness();
        when(assembler.assembleDefaults(any(), anyString(), anyMap(), any()))
                .thenThrow(new IllegalArgumentException("Unknown material"));

        assertNull(new CatalogItemRow(blueprint, "minecraft:not_real", assembler).menuItemStack());
        assertNull(new CatalogItemRow(blueprint, " ", assembler).menuItemStack());
        verify(assembler, never()).assembleDefaults(any(), eq(" "), anyMap(), any());
    }
}
