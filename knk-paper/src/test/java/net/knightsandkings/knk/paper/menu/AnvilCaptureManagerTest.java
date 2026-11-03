package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.MenuTextPrompt;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PandiO/knk-workspace#128: the anvil search/filter prompt on an {@link AnvilView}. The typed text
 * comes from the view's rename text, the result item names the search, the cost is 0, Cancel and
 * Escape run the cancel callback, and the anvil's items never reach the player. Bukkit types are
 * mocked; no server needed.
 */
class AnvilCaptureManagerTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000128");

    private final List<Runnable> ticks = new ArrayList<>();
    private final Map<ItemStack, MenuTextPrompt.Button> buttons = new IdentityHashMap<>();
    private final List<String> completed = new ArrayList<>();
    private int cancelled;

    private Plugin plugin;
    private Player player;
    private AnvilView view;
    private AnvilInventory top;
    private AnvilCaptureManager manager;

    @BeforeEach
    void setUp() {
        plugin = mock(Plugin.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(ID);
        top = mock(AnvilInventory.class);
        when(top.getSize()).thenReturn(3);
        view = mock(AnvilView.class);
        when(view.getTopInventory()).thenReturn(top);
        when(view.getPlayer()).thenReturn(player);
        when(player.getOpenInventory()).thenReturn(view);

        manager = new AnvilCaptureManager(plugin, (p, title) -> view, button -> {
            ItemStack item = mock(ItemStack.class);
            buttons.put(item, button);
            return item;
        }, ticks::add);
    }

    private void start(MenuTextPrompt prompt) {
        manager.startTextCapture(player, prompt, completed::add, () -> cancelled++);
    }

    private void runTicks() {
        List<Runnable> due = new ArrayList<>(ticks);
        ticks.clear();
        due.forEach(Runnable::run);
    }

    private InventoryClickEvent click(InventoryView clickedView, int rawSlot) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getView()).thenReturn(clickedView);
        when(event.getRawSlot()).thenReturn(rawSlot);
        return event;
    }

    private InventoryCloseEvent close(InventoryCloseEvent.Reason reason) {
        InventoryCloseEvent event = mock(InventoryCloseEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getView()).thenReturn(view);
        when(event.getReason()).thenReturn(reason);
        return event;
    }

    private MenuTextPrompt.Button prepare(String renameText) {
        when(view.getRenameText()).thenReturn(renameText);
        PrepareAnvilEvent event = mock(PrepareAnvilEvent.class);
        when(event.getView()).thenReturn(view);
        List<ItemStack> results = new ArrayList<>();
        org.mockito.Mockito.doAnswer(call -> results.add(call.getArgument(0))).when(event).setResult(any());
        manager.onPrepareAnvil(event);
        return results.isEmpty() ? null : buttons.get(results.get(results.size() - 1));
    }

    @Test
    void opensTheAnvilViewWithCancelOnTheLeftAndNoCost() {
        start(MenuTextPrompt.search("items", "Samurai"));

        ItemStack[] input = new ItemStack[1];
        verify(top).setItem(eq(AnvilCaptureManager.INPUT_SLOT), org.mockito.ArgumentMatchers.argThat(item -> {
            input[0] = item;
            return true;
        }));
        MenuTextPrompt.Button cancel = buttons.get(input[0]);
        assertEquals("Samurai", cancel.name());
        assertEquals("Cancel", cancel.lore().get(0));
        verify(view).setRepairCost(0);
        verify(player).openInventory(view);
        assertTrue(manager.isCapturing(ID));
    }

    @Test
    void theResultItemShowsTheTypedSearchAtCostZero() {
        start(MenuTextPrompt.search("items", null));

        MenuTextPrompt.Button result = prepare("Samurai");

        assertEquals("Search for \"Samurai\"", result.name());
        assertEquals(MenuTextPrompt.Tone.CONFIRM, result.tone());
        verify(view, org.mockito.Mockito.atLeast(2)).setRepairCost(0);
        verify(view).setRepairItemCountCost(0);
        assertEquals("Clear search", prepare("").name());
    }

    @Test
    void anAnvilThatIsNotAPromptIsLeftAlone() {
        start(MenuTextPrompt.search("items", null));
        AnvilView other = mock(AnvilView.class);
        when(other.getPlayer()).thenReturn(player);
        when(other.getTopInventory()).thenReturn(mock(AnvilInventory.class));
        PrepareAnvilEvent event = mock(PrepareAnvilEvent.class);
        when(event.getView()).thenReturn(other);

        manager.onPrepareAnvil(event);

        verify(event, never()).setResult(any());
    }

    @Test
    void confirmHandsTheTypedTextOverAndClosesNextTick() {
        start(MenuTextPrompt.search("items", null));
        when(view.getRenameText()).thenReturn("  Samurai ");
        InventoryClickEvent event = click(view, AnvilCaptureManager.RESULT_SLOT);

        manager.onClick(event);

        verify(event).setCancelled(true);
        verify(top).clear();
        assertTrue(completed.isEmpty(), "not inside the click handler");
        runTicks();
        verify(player).closeInventory();
        assertEquals(List.of("Samurai"), completed);
        assertEquals(0, cancelled);
        assertFalse(manager.isCapturing(ID));
    }

    @Test
    void confirmWithABlankFieldHandsOverEmptyText() {
        start(MenuTextPrompt.search("items", "Samurai"));
        when(view.getRenameText()).thenReturn("");

        manager.onClick(click(view, AnvilCaptureManager.RESULT_SLOT));
        runTicks();

        assertEquals(List.of(""), completed);
    }

    @Test
    void theLeftItemCancels() {
        start(MenuTextPrompt.search("items", null));

        manager.onClick(click(view, AnvilCaptureManager.INPUT_SLOT));
        runTicks();

        assertEquals(1, cancelled);
        assertTrue(completed.isEmpty());
        verify(player).closeInventory();
    }

    @Test
    void escapeCancelsAndEmptiesTheAnvilBeforeItCloses() {
        start(MenuTextPrompt.search("items", null));

        manager.onClose(close(InventoryCloseEvent.Reason.PLAYER));

        verify(top).clear();
        assertEquals(0, cancelled, "next tick, not inside the close event");
        runTicks();
        assertEquals(1, cancelled);
        assertFalse(manager.isCapturing(ID));
    }

    @Test
    void anotherInventoryOpeningEndsThePromptWithoutCallbacks() {
        start(MenuTextPrompt.search("items", null));

        manager.onClose(close(InventoryCloseEvent.Reason.OPEN_NEW));
        runTicks();

        verify(top).clear();
        assertEquals(0, cancelled);
        assertTrue(completed.isEmpty());
        assertFalse(manager.isCapturing(ID));
    }

    @Test
    void theCloseAfterConfirmIsNotACancel() {
        start(MenuTextPrompt.search("items", null));
        when(view.getRenameText()).thenReturn("Samurai");
        manager.onClick(click(view, AnvilCaptureManager.RESULT_SLOT));
        manager.onClose(close(InventoryCloseEvent.Reason.PLAYER)); // Escape before the next tick
        runTicks();

        assertEquals(List.of("Samurai"), completed);
        assertEquals(0, cancelled);
    }

    @Test
    void clicksInThePlayersOwnInventoryAreBlockedButDoNothing() {
        start(MenuTextPrompt.search("items", null));
        InventoryClickEvent event = click(view, 10);

        manager.onClick(event);
        runTicks();

        verify(event).setCancelled(true);
        assertEquals(0, cancelled);
        assertTrue(completed.isEmpty());
    }

    @Test
    void clicksInOtherInventoriesAreNotTouched() {
        start(MenuTextPrompt.search("items", null));
        InventoryView other = mock(InventoryView.class);
        when(other.getTopInventory()).thenReturn(mock(org.bukkit.inventory.Inventory.class));
        InventoryClickEvent event = click(other, AnvilCaptureManager.RESULT_SLOT);

        manager.onClick(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    void draggingIntoThePromptIsBlocked() {
        start(MenuTextPrompt.search("items", null));
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getView()).thenReturn(view);

        manager.onDrag(event);

        verify(event).setCancelled(true);
    }

    @Test
    void disablingThePluginClosesOpenPromptsWithoutCallbacks() {
        start(MenuTextPrompt.search("items", null));
        PluginDisableEvent event = mock(PluginDisableEvent.class);
        when(event.getPlugin()).thenReturn(plugin);

        manager.onPluginDisable(event);
        runTicks();

        verify(top).clear();
        verify(player).closeInventory();
        assertEquals(0, cancelled);
        assertFalse(manager.isCapturing(ID));
    }
}
