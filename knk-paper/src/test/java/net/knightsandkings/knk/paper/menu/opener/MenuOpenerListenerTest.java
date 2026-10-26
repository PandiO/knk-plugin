package net.knightsandkings.knk.paper.menu.opener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Audience;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Click;

/** PandiO/knk-workspace#160: which clicks open the menu, and that the item stays a pointer. */
class MenuOpenerListenerTest {

    private final MenuOpenerServiceTest.FakeItems items = new MenuOpenerServiceTest.FakeItems();
    private final Player player = mock(Player.class);
    private final PlayerInventory playerInventory = mock(PlayerInventory.class);
    private final List<Player> opened = new ArrayList<>();
    private final List<Runnable> scheduled = new ArrayList<>();
    private MenuOpenerSettings settings = MenuOpenerSettings.defaults();
    private long now = 1_000;
    private MenuOpenerListener listener;
    private ItemStack opener;
    private final ItemStack other = mock(ItemStack.class);
    private final java.util.Set<Inventory> ownTops = new java.util.HashSet<>();
    private final Block door = mock(Block.class);

    @BeforeEach
    void setUp() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getInventory()).thenReturn(playerInventory);
        when(player.isOnline()).thenReturn(true);
        MenuOpenerService service = new MenuOpenerService(() -> settings, uuid -> false, items, opened::add,
            (task, ticks) -> scheduled.add(task), () -> now, 3);
        listener = new MenuOpenerListener(service, top -> ownTops.contains(top), block -> block == door);
        opener = items.create(settings);
    }

    private PlayerInteractEvent interact(Action action, ItemStack item, Block block) {
        return new PlayerInteractEvent(player, action, item, block, org.bukkit.block.BlockFace.UP, EquipmentSlot.HAND);
    }

    private void nextClick() {
        now += MenuOpenerService.CLICK_COOLDOWN_MILLIS + 1;
    }

    // ==================== clicks ====================

    @Test
    void rightAndLeftClicksInTheAirOpenTheMenu() {
        PlayerInteractEvent right = interact(Action.RIGHT_CLICK_AIR, opener, null);
        listener.onInteract(right);
        assertEquals(Event.Result.DENY, right.useItemInHand());
        nextClick();
        listener.onInteract(interact(Action.LEFT_CLICK_AIR, opener, null));
        assertEquals(2, opened.size());
    }

    @Test
    void anotherItemDoesNothing() {
        listener.onInteract(interact(Action.RIGHT_CLICK_AIR, other, null));
        assertTrue(opened.isEmpty());
    }

    @Test
    void aDoorKeepsWorkingUnlessThePlayerSneaks() {
        listener.onInteract(interact(Action.RIGHT_CLICK_BLOCK, opener, door));
        assertTrue(opened.isEmpty());

        when(player.isSneaking()).thenReturn(true);
        listener.onInteract(interact(Action.RIGHT_CLICK_BLOCK, opener, door));
        assertEquals(1, opened.size());
    }

    @Test
    void onlyTheConfiguredClicksOpenIt() {
        settings = new MenuOpenerSettings(true, Audience.ALL, true, true, null, null, null, 8,
            Set.of(Click.RIGHT_CLICK), false, false);
        listener.onInteract(interact(Action.LEFT_CLICK_AIR, opener, null));
        assertTrue(opened.isEmpty());
        listener.onInteract(interact(Action.RIGHT_CLICK_AIR, opener, null));
        assertEquals(1, opened.size());
    }

    @Test
    void aUseSomebodyDeniedIsLeftAlone() {
        PlayerInteractEvent event = interact(Action.RIGHT_CLICK_AIR, opener, null);
        event.setUseItemInHand(Event.Result.DENY);
        listener.onInteract(event);
        assertTrue(opened.isEmpty());
    }

    // ==================== inventories ====================

    private InventoryClickEvent click(boolean ownView, boolean inTop, ItemStack current, ItemStack cursor) {
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        if (ownView) {
            ownTops.add(top);
        }
        when(view.getTopInventory()).thenReturn(top);
        when(view.getPlayer()).thenReturn(player);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(inTop ? top : playerInventory);
        when(event.getCurrentItem()).thenReturn(current);
        when(event.getCursor()).thenReturn(cursor);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        return event;
    }

    @Test
    void aClickOnItInTheOwnInventoryOpensTheMenuAndMovesNothing() {
        InventoryClickEvent event = click(true, false, opener, null);
        listener.onInventoryClick(event);
        verify(event).setCancelled(true);
        verify(player).closeInventory();
        assertEquals(1, scheduled.size());
    }

    @Test
    void itNeverGoesIntoAChestEvenWhenMovable() {
        settings = new MenuOpenerSettings(true, Audience.ALL, true, true, null, null, null, 8,
            EnumSet.allOf(Click.class), false, true);
        InventoryClickEvent shift = click(false, false, opener, null);
        listener.onInventoryClick(shift);
        verify(shift).setCancelled(true);

        InventoryClickEvent intoGrid = click(true, true, null, opener);
        listener.onInventoryClick(intoGrid);
        verify(intoGrid).setCancelled(true);

        InventoryClickEvent within = click(true, false, opener, null);
        listener.onInventoryClick(within);
        verify(within, org.mockito.Mockito.never()).setCancelled(true);
        assertTrue(scheduled.isEmpty(), "a movable item is picked up, not used");
    }

    @Test
    void otherItemsMoveFreely() {
        InventoryClickEvent event = click(false, true, other, null);
        listener.onInventoryClick(event);
        verify(event, org.mockito.Mockito.never()).setCancelled(true);
    }

    // ==================== never a game item ====================

    @Test
    void itCannotBeDroppedUnlessDroppableAndThenItIsGone() {
        Item entity = mock(Item.class);
        when(entity.getItemStack()).thenReturn(opener);
        PlayerDropItemEvent drop = new PlayerDropItemEvent(player, entity);
        listener.onDrop(drop);
        assertTrue(drop.isCancelled());

        settings = new MenuOpenerSettings(true, Audience.ALL, true, true, null, null, null, 8,
            EnumSet.allOf(Click.class), true, false);
        PlayerDropItemEvent allowed = new PlayerDropItemEvent(player, entity);
        listener.onDrop(allowed);
        assertFalse(allowed.isCancelled());
        verify(entity).remove();
    }

    @Test
    void itNeverDropsOnDeath() {
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        List<ItemStack> drops = new ArrayList<>(List.of(opener, other));
        when(death.getDrops()).thenReturn(drops);
        listener.onDeath(death);
        assertEquals(List.of(other), drops);
    }
}
