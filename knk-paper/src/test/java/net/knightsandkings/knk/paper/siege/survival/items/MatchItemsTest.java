package net.knightsandkings.knk.paper.siege.survival.items;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Match item tags and tracking (DESIGN §8b). The World mock is a field (CLAUDE.md). */
public class MatchItemsTest {

    private final World world = mock(World.class);
    private final MatchItems items = new MatchItems(new NamespacedKey("knk", "survival_match"));

    /** An item stack whose meta carries a string-capable persistent data container. */
    public static ItemStack itemWithPdc(Material type) {
        Map<Object, Object> values = new HashMap<>();
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        doAnswer(inv -> values.put(inv.getArgument(0), inv.getArgument(2))).when(pdc).set(any(), eq(PersistentDataType.STRING), any());
        when(pdc.get(any(), eq(PersistentDataType.STRING))).thenAnswer(inv -> values.get(inv.getArgument(0)));
        ItemMeta meta = mock(ItemMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(type);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(item.clone()).thenReturn(item);
        when(item.getAmount()).thenReturn(1);
        return item;
    }

    @Test
    void tagsAndReadsTheMatchId() {
        ItemStack sword = itemWithPdc(Material.IRON_SWORD);
        assertNull(items.matchIdOf(sword));
        items.tag(sword, "m-1");
        assertEquals("m-1", items.matchIdOf(sword));
        assertTrue(items.isMatchItem(sword, "m-1"));
        assertFalse(items.isMatchItem(sword, "m-2"));
        assertNull(items.matchIdOf(null));
        ItemStack air = mock(ItemStack.class);
        when(air.getType()).thenReturn(Material.AIR);
        assertNull(items.matchIdOf(air));
        assertEquals(air, items.tag(air, "m-1"));
    }

    @Test
    void theStampWorksOnAMetaForTheAssembler() {
        ItemStack bow = itemWithPdc(Material.BOW);
        Consumer<ItemMeta> stamp = items.stamp("m-7");
        stamp.accept(bow.getItemMeta());
        assertEquals("m-7", items.matchIdOf(bow));
    }

    @Test
    void stripRemovesOnlyTheMatchsItemsFromThePlayer() {
        ItemStack mine = itemWithPdc(Material.IRON_SWORD);
        items.tag(mine, "m-1");
        ItemStack other = itemWithPdc(Material.BOW);
        items.tag(other, "m-2");
        ItemStack plain = itemWithPdc(Material.BREAD);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getContents()).thenReturn(new ItemStack[] {mine, other, plain, null});
        when(inventory.getItemInOffHand()).thenReturn(mine);
        Player player = mock(Player.class);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getItemOnCursor()).thenReturn(plain);

        assertEquals(2, items.stripFromPlayer(player, "m-1"));
        verify(inventory).setItem(0, null);
        verify(inventory).setItemInOffHand(null);
        assertEquals(1, items.countOnPlayer(player, "m-1"));
    }

    @Test
    void giveDropsTheOverflowAsOwnedMatchItemsAndTracksThem() {
        ItemStack stack = itemWithPdc(Material.ARROW);
        ItemStack rest = itemWithPdc(Material.ARROW);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.addItem(stack)).thenReturn(new HashMap<>(Map.of(0, rest)));
        UUID owner = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(owner);
        when(player.getInventory()).thenReturn(inventory);
        Location feet = new Location(world, 1, 64, 1);
        when(player.getLocation()).thenReturn(feet);
        Item dropped = mock(Item.class);
        UUID droppedId = UUID.randomUUID();
        when(dropped.getUniqueId()).thenReturn(droppedId);
        when(world.dropItem(eq(feet), eq(rest), any())).thenAnswer(inv -> {
            Consumer<Item> init = inv.getArgument(2);
            init.accept(dropped);
            return dropped;
        });

        assertEquals(1, items.give(player, List.of(stack), "m-1"));
        assertEquals("m-1", items.matchIdOf(stack));
        assertEquals("m-1", items.matchIdOf(rest));
        verify(dropped).setOwner(owner);
        verify(dropped).setCanMobPickup(false);
        verify(dropped).setPersistent(false);
        assertEquals(1, items.trackedGroundItems("m-1"));
        assertEquals(List.of("m-1"), items.trackedMatches());
    }

    @Test
    void removeGroundTakesTrackedAndTaggedItemsAndSweepTakesAnyTaggedItem() {
        ItemStack tagged = itemWithPdc(Material.ARROW);
        items.tag(tagged, "m-1");
        Item onGround = mock(Item.class);
        when(onGround.isValid()).thenReturn(true);
        when(onGround.getItemStack()).thenReturn(tagged);
        ItemStack plain = itemWithPdc(Material.BREAD);
        Item stray = mock(Item.class);
        when(stray.isValid()).thenReturn(true);
        when(stray.getItemStack()).thenReturn(plain);
        when(world.getEntitiesByClass(Item.class)).thenReturn(List.of(onGround, stray));

        assertEquals(1, items.removeGround("m-1", List.of(world)));
        verify(onGround).remove();
        verify(stray, org.mockito.Mockito.never()).remove();
        assertEquals(0, items.trackedGroundItems("m-1"));

        assertEquals(1, items.sweep(List.of(onGround, stray)));
    }

    @Test
    void dropTagsTheCloneNotTheOriginalReferenceOnlyWhenCloned() {
        ItemStack stack = itemWithPdc(Material.ARROW);
        Item dropped = mock(Item.class);
        when(dropped.getUniqueId()).thenReturn(UUID.randomUUID());
        ArgumentCaptor<ItemStack> captor = ArgumentCaptor.forClass(ItemStack.class);
        when(world.dropItem(any(), captor.capture(), any())).thenReturn(dropped);
        Location at = new Location(world, 0, 64, 0);
        items.drop(at, stack, null, "m-3");
        assertEquals("m-3", items.matchIdOf(captor.getValue()));
    }
}
