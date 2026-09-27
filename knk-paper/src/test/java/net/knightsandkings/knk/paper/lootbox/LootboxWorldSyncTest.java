package net.knightsandkings.knk.paper.lootbox;

import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import net.knightsandkings.knk.core.lootbox.KnkLootboxWorldChange;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static net.knightsandkings.knk.paper.mapper.LootboxTokenTagTest.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** DESIGN.md §3.9: a LootboxWorldChanged notification takes despawned boxes down and revoked tokens away at once. */
class LootboxWorldSyncTest {

    @Test
    void removedBoxesGoAway_andRevokedTokensLeaveEveryOnlineInventory() {
        UUID revoked = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        Player holder = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        Inventory enderChest = mock(Inventory.class);
        ItemStack revokedCopy = token(revoked, 1);
        ItemStack keptCopy = token(kept, 1);
        when(holder.getInventory()).thenReturn(inventory);
        when(holder.getEnderChest()).thenReturn(enderChest);
        when(holder.getName()).thenReturn("Steve");
        when(inventory.getContents()).thenReturn(new ItemStack[]{keptCopy, revokedCopy});
        when(enderChest.getContents()).thenReturn(new ItemStack[0]);
        List<Integer> gone = new ArrayList<>();

        LootboxWorldSync sync = new LootboxWorldSync(gone::add, () -> List.of(holder));
        sync.handle(new PlayerNotification(5, 0, null, "", PlayerNotification.TYPE_LOOTBOX_WORLD_CHANGED, null, null, null,
                new KnkLootboxWorldChange(List.of(12, 13), List.of(revoked))));

        assertEquals(List.of(12, 13), gone);
        verify(inventory).setItem(1, null);
        verify(inventory, never()).setItem(eq(0), any());
        verify(holder).sendMessage(contains("revoked by staff"));
    }

    @Test
    void aNotificationWithoutPayload_changesNothing() {
        List<Integer> gone = new ArrayList<>();
        new LootboxWorldSync(gone::add, List::of).handle(
                new PlayerNotification(5, 0, null, "", PlayerNotification.TYPE_LOOTBOX_WORLD_CHANGED, null));

        assertEquals(List.of(), gone);
    }
}
