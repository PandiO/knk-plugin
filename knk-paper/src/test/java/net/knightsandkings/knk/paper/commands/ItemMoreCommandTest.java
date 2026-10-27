package net.knightsandkings.knk.paper.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.ports.api.ItemInstancesApi;
import net.knightsandkings.knk.paper.mapper.ItemInstanceTag;
import net.knightsandkings.knk.paper.mapper.ItemInstanceTagTest;

/** PandiO/knk-workspace#175: {@code /knk item more} for plain stacks and for items carrying an instance id. */
class ItemMoreCommandTest {

    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final ItemInstancesApi api = mock(ItemInstancesApi.class);
    private final UUID uuid = UUID.randomUUID();
    private final ItemMoreCommand command = new ItemMoreCommand(Runnable::run,
        id -> CompletableFuture.completedFuture(id.equals(uuid) ? 42 : null), api);
    private ItemStack[] storage = new ItemStack[36];

    ItemMoreCommandTest() {
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("Admin");
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenAnswer(inv -> storage);
        when(inventory.firstEmpty()).thenAnswer(inv -> {
            for (int i = 0; i < storage.length; i++) {
                if (storage[i] == null) {
                    return i;
                }
            }
            return -1;
        });
        when(inventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
    }

    private ItemStack plain(Material type, int amount, int max) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(type);
        when(item.getAmount()).thenReturn(amount);
        when(item.getMaxStackSize()).thenReturn(max);
        when(item.hasItemMeta()).thenReturn(false);
        ItemStack copy = mock(ItemStack.class);
        when(item.clone()).thenReturn(copy);
        when(inventory.getItemInMainHand()).thenReturn(item);
        return item;
    }

    private void fill(int freeSlots) {
        storage = new ItemStack[36];
        for (int i = freeSlots; i < storage.length; i++) {
            ItemStack stone = mock(ItemStack.class);
            when(stone.getType()).thenReturn(Material.STONE);
            storage[i] = stone;
        }
    }

    @Test
    void aStackBelowItsMaxIsFilled() {
        ItemStack bread = plain(Material.BREAD, 10, 64);

        command.execute(player);

        verify(bread).setAmount(64);
        verify(inventory).setItemInMainHand(bread);
        verify(player).sendMessage(contains("Filled your stack to 64"));
    }

    @Test
    void aFullStackGivesAnotherFullStackInAFreeSlot() {
        ItemStack bread = plain(Material.BREAD, 64, 64);
        fill(3);

        command.execute(player);

        ItemStack copy = bread.clone();
        verify(copy).setAmount(64);
        verify(inventory).setItem(0, copy);
        verify(player).sendMessage(contains("another stack of 64"));
    }

    @Test
    void aFullStackWithoutRoomIsRefused() {
        plain(Material.BREAD, 64, 64);
        fill(0);

        command.execute(player);

        verify(inventory, never()).setItem(anyInt(), any());
        verify(player).sendMessage(contains("no room"));
    }

    @Test
    void anEmptyHandOrTheConsoleIsRefused() {
        plain(Material.AIR, 0, 64);
        command.execute(player);
        verify(player).sendMessage(contains("hold an item"));

        CommandSender console = mock(CommandSender.class);
        command.execute(console);
        verify(console).sendMessage(contains("Only players"));
    }

    /** A held instance item (real PDC map) with the given amount and max stack size. */
    private ItemStack instanceItem(long id, int amount, int max) {
        ItemStack sword = ItemInstanceTagTest.itemWithPdc();
        ItemInstanceTag.stamp(sword.getItemMeta(), id);
        when(sword.getType()).thenReturn(Material.DIAMOND_SWORD);
        when(sword.getAmount()).thenReturn(amount);
        when(sword.getMaxStackSize()).thenReturn(max);
        List<ItemStack> copies = new ArrayList<>();
        when(sword.clone()).thenAnswer(inv -> {
            ItemStack copy = ItemInstanceTagTest.itemWithPdc();
            ItemInstanceTag.stamp(copy.getItemMeta(), id);
            when(copy.clone()).thenAnswer(again -> {
                ItemStack single = ItemInstanceTagTest.itemWithPdc();
                ItemInstanceTag.stamp(single.getItemMeta(), id);
                copies.add(single);
                return single;
            });
            return copy;
        });
        when(inventory.getItemInMainHand()).thenReturn(sword);
        return sword;
    }

    @Test
    void anInstanceItemGetsOneCopyWithANewlyMintedId() {
        instanceItem(500, 1, 1);
        when(api.cloneAsync(500, 1, 42, 42)).thenReturn(CompletableFuture.completedFuture(List.of(501L)));

        command.execute(player);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<ItemStack[]> given = org.mockito.ArgumentCaptor.forClass(ItemStack[].class);
        verify(inventory).addItem(given.capture());
        assertEquals(1, given.getValue().length);
        assertEquals(Optional.of(501L), ItemInstanceTag.read(given.getValue()[0]));
        verify(given.getValue()[0]).setAmount(1);
        verify(player).sendMessage(contains("new instance id (501)"));
    }

    @Test
    void aStackableInstanceItemGetsSingleCopiesUpToTheMissingAmountAndTheFreeSlots() {
        instanceItem(700, 13, 16);
        fill(2);
        when(api.cloneAsync(eq(700L), eq(2), eq(42), eq(42)))
            .thenReturn(CompletableFuture.completedFuture(List.of(701L, 702L)));

        command.execute(player);

        verify(api).cloneAsync(700L, 2, 42, 42);
        verify(player).sendMessage(contains("Gave you 2 copies"));
    }

    @Test
    void aFailedMintGivesNothing() {
        instanceItem(500, 1, 1);
        when(api.cloneAsync(anyLong(), anyInt(), any(), any()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("409 Stackable")));

        command.execute(player);

        verify(inventory, never()).addItem(any(ItemStack[].class));
        verify(player).sendMessage(contains("Nothing was given"));
    }

    @Test
    void anInstanceItemWithoutRoomOrApiIsRefused() {
        instanceItem(500, 1, 1);
        fill(0);
        command.execute(player);
        verify(api, never()).cloneAsync(anyLong(), anyInt(), any(), any());
        verify(player).sendMessage(contains("no room for a copy"));

        ItemMoreCommand offline = new ItemMoreCommand(Runnable::run, id -> CompletableFuture.completedFuture(null), null);
        offline.execute(player);
        verify(player).sendMessage(contains("can't be made right now"));
        assertTrue(ItemMoreCommand.NODE.startsWith("knk.admin.item."));
    }
}
