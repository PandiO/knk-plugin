package net.knightsandkings.knk.paper.lootbox;

import net.knightsandkings.knk.core.lootbox.KnkLootboxToken;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import net.knightsandkings.knk.core.ports.api.LootboxesQueryApi;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.knightsandkings.knk.paper.mapper.LootboxTokenTagTest.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Lootboxes Phase 5: handing token items over (never twice) and taking spent copies away. */
class LootboxTokenDeliveryTest {

    private final UUID held = UUID.randomUUID();
    private final UUID fresh = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final Inventory enderChest = mock(Inventory.class);
    private final LootboxesQueryApi queryApi = mock(LootboxesQueryApi.class);
    private final LootboxesCommandApi commandApi = mock(LootboxesCommandApi.class);
    private final ItemStack built = mock(ItemStack.class);
    private LootboxTokenDelivery delivery;

    private static KnkLootboxToken tokenOf(UUID id) {
        return new KnkLootboxToken(1, id, 3, "Weapons Lootbox", "Weapons", 5, "Legendary Weapons Lootbox", "Issued", "Admin", 9);
    }

    @BeforeEach
    void setUp() {
        when(player.isOnline()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getInventory()).thenReturn(inventory);
        when(player.getEnderChest()).thenReturn(enderChest);
        ItemStack heldItem = token(held, 1); // built first: it stubs its own mocks
        when(inventory.getContents()).thenReturn(new ItemStack[]{null, heldItem});
        when(enderChest.getContents()).thenReturn(new ItemStack[0]);
        when(inventory.addItem(built)).thenReturn(new HashMap<>());
        when(commandApi.markTokensDelivered(anyInt(), anyList())).thenReturn(CompletableFuture.completedFuture(null));
        delivery = new LootboxTokenDelivery(Runnable::run, queryApi, commandApi, LootboxSettings::defaults, p -> 9, t -> built);
    }

    @Test
    void give_skipsATokenAlreadyHeld_andConfirmsBoth() {
        int given = delivery.give(player, 9, List.of(tokenOf(held), tokenOf(fresh)));

        assertEquals(1, given);
        verify(inventory, times(1)).addItem(built);
        verify(commandApi).markTokensDelivered(9, List.of(held, fresh));
    }

    @Test
    void deliverUndelivered_fetchesAndGives() {
        when(queryApi.getUndeliveredTokens(9)).thenReturn(CompletableFuture.completedFuture(List.of(tokenOf(fresh))));

        delivery.deliverUndelivered(player);

        verify(inventory).addItem(built);
        verify(player).sendMessage(org.mockito.ArgumentMatchers.contains("You received 1 lootbox"));
    }

    @Test
    void deliverUndelivered_whileAFetchIsInFlight_runsOnceMoreAfterIt() {
        CompletableFuture<List<KnkLootboxToken>> first = new CompletableFuture<>();
        when(queryApi.getUndeliveredTokens(9)).thenReturn(first, CompletableFuture.completedFuture(List.of()));

        delivery.deliverUndelivered(player);
        delivery.deliverUndelivered(player);
        verify(queryApi, times(1)).getUndeliveredTokens(9);

        first.complete(List.of());
        verify(queryApi, times(2)).getUndeliveredTokens(9);
    }

    @Test
    void removeOne_decrementsAStack_orClearsTheLastCopy() {
        ItemStack stack = token(held, 3);
        when(inventory.getContents()).thenReturn(new ItemStack[]{null, stack});
        when(inventory.getHeldItemSlot()).thenReturn(0);

        assertTrue(LootboxTokenDelivery.removeOne(player, held));
        verify(stack).setAmount(2);
        verify(inventory).setItem(1, stack);

        ItemStack single = token(held, 1);
        when(inventory.getContents()).thenReturn(new ItemStack[]{single});
        assertTrue(LootboxTokenDelivery.removeOne(player, held));
        verify(inventory).setItem(0, null);

        assertFalse(LootboxTokenDelivery.removeOne(player, fresh));
    }

    @Test
    void removeAll_takesEveryCopyOfASpentToken_andNothingElse() {
        ItemStack other = token(fresh, 1);
        ItemStack first = token(held, 2);
        ItemStack last = token(held, 1);
        when(inventory.getContents()).thenReturn(new ItemStack[]{first, other, last});

        assertEquals(3, LootboxTokenDelivery.removeAll(player, held));
        verify(inventory).setItem(0, null);
        verify(inventory).setItem(2, null);
        verify(inventory, never()).setItem(eq(1), any());
    }

    @Test
    void theJoinScan_removesRevokedAndOpenedCopies_andLeavesTheRest() {
        UUID revoked = UUID.randomUUID();
        UUID opened = UUID.randomUUID();
        ItemStack live = token(held, 1);
        ItemStack revokedCopy = token(revoked, 1);
        ItemStack openedCopy = token(opened, 2);
        ItemStack unknownCopy = token(fresh, 1);
        when(inventory.getContents()).thenReturn(new ItemStack[]{live, revokedCopy, openedCopy});
        when(enderChest.getContents()).thenReturn(new ItemStack[]{unknownCopy});
        when(queryApi.getTokenStatuses(anyList())).thenReturn(CompletableFuture.completedFuture(java.util.Map.of(
                held, "Issued", revoked, "Revoked", opened, "Redeemed", fresh, "Unknown")));

        delivery.removeDeadCopies(player);

        verify(inventory).setItem(1, null);
        verify(inventory).setItem(2, null);
        verify(inventory, never()).setItem(eq(0), any());
        verify(enderChest, never()).setItem(anyInt(), any());
        verify(player).sendMessage(org.mockito.ArgumentMatchers.contains("revoked by staff"));
        verify(player).sendMessage(org.mockito.ArgumentMatchers.contains("already opened"));
    }

    @Test
    void theJoinScan_withNoTokens_asksNothing() {
        when(inventory.getContents()).thenReturn(new ItemStack[0]);

        delivery.removeDeadCopies(player);

        verify(queryApi, never()).getTokenStatuses(anyList());
    }

    @Test
    void aRevokedToken_isAlsoTakenFromTheEnderChest() {
        ItemStack stored = token(held, 1);
        when(inventory.getContents()).thenReturn(new ItemStack[0]);
        when(enderChest.getContents()).thenReturn(new ItemStack[]{stored});

        assertTrue(LootboxTokenDelivery.removeDead(player, held, "Revoked"));

        verify(enderChest).setItem(0, null);
    }
}
