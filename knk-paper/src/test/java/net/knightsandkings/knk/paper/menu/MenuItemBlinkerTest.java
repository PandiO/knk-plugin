package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KNG-125: the item blinker swaps only the Inventory's ItemStack - item, pane, item - every
 * interval, leaves the click-routing snapshot alone, keeps out of slots something else replaced,
 * and stops for a slot a re-render made non-blinking. Bukkit types are mocked; no server needed.
 */
class MenuItemBlinkerTest {

    private static final long START = 1_000L;
    private static final int SLOT = 4;

    private final Map<Integer, ItemStack> slots = new HashMap<>();
    private Inventory inventory;
    private ItemStack original;
    private ItemStack pane;
    private MenuItemBlinker blinker;

    private static ItemStack stack(Material material) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        return stack;
    }

    private static MenuRenderResult render(Map<Integer, ItemStack> stacks, Map<Integer, Integer> blinking) {
        return new MenuRenderResult(stacks, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                MenuContextParams.EMPTY, 27, blinking);
    }

    @BeforeEach
    void setUp() {
        inventory = mock(Inventory.class);
        when(inventory.getItem(anyInt())).thenAnswer(call -> slots.get(call.<Integer>getArgument(0)));
        org.mockito.Mockito.doAnswer(call -> {
            slots.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(inventory).setItem(anyInt(), any());
        original = stack(Material.DIAMOND_SWORD);
        pane = stack(MenuItemBlinker.FLASH_MATERIAL);
        blinker = new MenuItemBlinker(from -> {
            assertSame(original, from, "the pane is built from the item it replaces");
            return pane;
        });
        slots.put(SLOT, original);
    }

    private OpenMenuContext context(MenuRenderResult result) {
        return new OpenMenuContext(mock(Player.class), inventory, mock(RuntimeMenu.class), result, START);
    }

    @Test
    void theSlotAlternatesBetweenTheItemAndThePaneEveryInterval() {
        OpenMenuContext context = context(render(Map.of(SLOT, original), Map.of(SLOT, 10)));

        blinker.tick(context, START + 5, false);
        assertSame(original, slots.get(SLOT));

        blinker.tick(context, START + 10, false);
        assertSame(pane, slots.get(SLOT));
        assertTrue(context.flashedSlots().contains(SLOT));

        blinker.tick(context, START + 15, false);
        assertSame(pane, slots.get(SLOT), "no write while the phase is unchanged");

        blinker.tick(context, START + 20, false);
        assertSame(original, slots.get(SLOT));
        assertTrue(context.flashedSlots().isEmpty());
    }

    @Test
    void theClickRoutingSnapshotIsNotTouched() {
        MenuRenderResult result = render(Map.of(SLOT, original), Map.of(SLOT, 10));
        OpenMenuContext context = context(result);

        blinker.tick(context, START + 10, false);

        assertSame(result.itemsBySlot(), context.itemsBySlot(), "clicks still resolve through the last render");
    }

    @Test
    void aSlotSomethingElseReplacedIsLeftAlone() {
        ItemStack other = stack(Material.APPLE);
        slots.put(SLOT, other);
        OpenMenuContext context = context(render(Map.of(SLOT, original), Map.of(SLOT, 10)));

        blinker.tick(context, START + 10, false);

        assertSame(other, slots.get(SLOT));
        verify(inventory, never()).setItem(anyInt(), any());
    }

    @Test
    void aReRenderToANonBlinkingItemStopsTheBlink() {
        OpenMenuContext context = context(render(Map.of(SLOT, original), Map.of(SLOT, 10)));
        blinker.tick(context, START + 10, false);
        assertSame(pane, slots.get(SLOT));

        // The re-render wrote its own item into the slot (applyToInventory), and it no longer blinks.
        ItemStack replacement = stack(Material.EMERALD);
        slots.put(SLOT, replacement);
        context.update(mock(RuntimeMenu.class), render(Map.of(SLOT, replacement), Map.of()));

        for (long tick = START + 11; tick <= START + 60; tick++) {
            blinker.tick(context, tick, false);
        }
        assertSame(replacement, slots.get(SLOT));
        assertTrue(context.flashedSlots().isEmpty());
    }

    @Test
    void aReRenderThatStillBlinksKeepsTheRhythm() {
        OpenMenuContext context = context(render(Map.of(SLOT, original), Map.of(SLOT, 10)));
        blinker.tick(context, START + 12, false);
        assertSame(pane, slots.get(SLOT));

        // Same item re-rendered mid-pane-phase: the render restored the item; the blinker puts the pane back.
        slots.put(SLOT, original);
        context.update(mock(RuntimeMenu.class), render(Map.of(SLOT, original), Map.of(SLOT, 10)));
        blinker.tick(context, START + 13, false);

        assertSame(pane, slots.get(SLOT));
    }

    @Test
    void eachSlotUsesItsOwnInterval() {
        ItemStack slowOriginal = stack(Material.DIAMOND_SWORD);
        slots.put(7, slowOriginal);
        MenuItemBlinker anyPane = new MenuItemBlinker(from -> pane);
        OpenMenuContext context = context(render(Map.of(SLOT, original, 7, slowOriginal), Map.of(SLOT, 10, 7, 30)));

        anyPane.tick(context, START + 10, false);

        assertSame(pane, slots.get(SLOT));
        assertSame(slowOriginal, slots.get(7));
        assertEquals(List.of(SLOT), List.copyOf(context.flashedSlots()));
    }
}
