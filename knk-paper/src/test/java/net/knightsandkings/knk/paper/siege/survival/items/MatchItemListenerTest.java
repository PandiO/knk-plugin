package net.knightsandkings.knk.paper.siege.survival.items;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The pickup guards of DESIGN §8b: members of the item's match pick it up, nobody and nothing else does. */
class MatchItemListenerTest {

    private final MatchItems items = new MatchItems(new NamespacedKey("knk", "survival_match"));
    private final Map<UUID, String> matchOf = new HashMap<>();
    private final MatchItemListener listener = new MatchItemListener(items, matchOf::get);

    private Item ground(String tag) {
        ItemStack stack = MatchItemsTest.itemWithPdc(Material.ARROW);
        if (tag != null) items.tag(stack, tag);
        Item item = mock(Item.class);
        when(item.getItemStack()).thenReturn(stack);
        return item;
    }

    private Player player(String match) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        if (match != null) matchOf.put(id, match);
        return player;
    }

    @SuppressWarnings("deprecation")
    @Test
    void aMemberOfTheItemsMatchPicksItUpEvenAfterTheSiegeGuardCancelled() {
        PlayerPickupItemEvent event = new PlayerPickupItemEvent(player("m-1"), ground("m-1"), 0);
        event.setCancelled(true); // the Siege inventory guard at HIGH
        listener.onPlayerPickup(event);
        assertFalse(event.isCancelled());
    }

    @SuppressWarnings("deprecation")
    @Test
    void outsidersAndOtherMatchesAreDeniedAndPlainItemsAreLeftAlone() {
        PlayerPickupItemEvent other = new PlayerPickupItemEvent(player("m-2"), ground("m-1"), 0);
        listener.onPlayerPickup(other);
        assertTrue(other.isCancelled());

        PlayerPickupItemEvent outsider = new PlayerPickupItemEvent(player(null), ground("m-1"), 0);
        listener.onPlayerPickup(outsider);
        assertTrue(outsider.isCancelled());

        PlayerPickupItemEvent plain = new PlayerPickupItemEvent(player(null), ground(null), 0);
        listener.onPlayerPickup(plain);
        assertFalse(plain.isCancelled());
    }

    @Test
    void mobsAndHoppersNeverTakeMatchItems() {
        EntityPickupItemEvent mob = new EntityPickupItemEvent(mock(Zombie.class), ground("m-1"), 0);
        listener.onEntityPickup(mob);
        assertTrue(mob.isCancelled());

        EntityPickupItemEvent plainMob = new EntityPickupItemEvent(mock(Zombie.class), ground(null), 0);
        listener.onEntityPickup(plainMob);
        assertFalse(plainMob.isCancelled());

        InventoryPickupItemEvent hopper = new InventoryPickupItemEvent(mock(Inventory.class), ground("m-1"));
        listener.onInventoryPickup(hopper);
        assertTrue(hopper.isCancelled());
    }
}
