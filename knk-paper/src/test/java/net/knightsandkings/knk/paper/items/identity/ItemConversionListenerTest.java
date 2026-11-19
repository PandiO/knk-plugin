package net.knightsandkings.knk.paper.items.identity;

import io.papermc.paper.event.player.PlayerPurchaseEvent;
import net.knightsandkings.knk.core.items.identity.ItemConversionLog.Count;
import net.knightsandkings.knk.paper.mapper.BlueprintTag;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.Item;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static net.knightsandkings.knk.paper.items.identity.ItemIdentityTestSupport.named;
import static net.knightsandkings.knk.paper.items.identity.ItemIdentityTestSupport.service;
import static net.knightsandkings.knk.paper.items.identity.ItemIdentityTestSupport.taggedPlain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PandiO/knk-workspace#141: each conversion hook of DESIGN §8 resolves and counts what a player obtains, removes a stale
 * blueprint tag from an otherwise plain item and leaves everything else as it was. Events are Mockito mocks around
 * MockBukkit items and worlds (the world is kept in a field).
 */
class ItemConversionListenerTest {

    private ServerMock server;
    private WorldMock world;
    private PlayerMock player;
    private ItemIdentityService service;
    private ItemConversionListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("identity");
        player = server.addPlayer();
        service = service(new ItemIdentityTestSupport.FakeApi(), true);
        listener = new ItemConversionListener(service);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private Item drop(ItemStack stack) {
        return world.dropItem(new Location(world, 0, 64, 0), stack);
    }

    @Test
    void pickup_byAPlayer_isCounted_andAStaleTagIsRemovedFromTheEntitysItem() {
        Item item = drop(taggedPlain(Material.OAK_LOG, 8, 10));
        EntityPickupItemEvent event = mock(EntityPickupItemEvent.class);
        when(event.getEntity()).thenReturn(player);
        when(event.getItem()).thenReturn(item);

        listener.onPickup(event);

        assertEquals(Optional.empty(), BlueprintTag.read(item.getItemStack()));
        assertTrue(item.getItemStack().isSimilar(new ItemStack(Material.OAK_LOG)));
        assertEquals(List.of(new Count("pickup", "normalized", "minecraft:oak_log", 8)), service.conversionLog().drain());
    }

    @Test
    void pickup_byAnotherEntity_isNotCounted() {
        EntityPickupItemEvent event = mock(EntityPickupItemEvent.class);
        when(event.getEntity()).thenReturn(world.spawn(new Location(world, 0, 64, 0), org.bukkit.entity.Zombie.class));
        when(event.getItem()).thenReturn(drop(new ItemStack(Material.OAK_LOG)));

        listener.onPickup(event);

        assertTrue(service.conversionLog().drain().isEmpty());
    }

    @Test
    void blockDrops_areCounted_plainItemsUnchanged() {
        Item log = drop(new ItemStack(Material.OAK_LOG, 1));
        BlockDropItemEvent event = mock(BlockDropItemEvent.class);
        when(event.getItems()).thenReturn(new ArrayList<>(List.of(log)));

        listener.onBlockDrop(event);

        assertTrue(log.getItemStack().isSimilar(new ItemStack(Material.OAK_LOG)));
        assertEquals(List.of(new Count("block_drop", "vanilla_match", "minecraft:oak_log", 1)), service.conversionLog().drain());
    }

    @Test
    void deathDrops_areNormalisedInPlace_andCustomItemsLeftAlone() {
        ItemStack stale = taggedPlain(Material.BREAD, 2, 20);
        ItemStack custom = named(Material.BREAD, "Ration from another plugin");
        EntityDeathEvent event = mock(EntityDeathEvent.class);
        when(event.getDrops()).thenReturn(new ArrayList<>(List.of(stale, custom)));

        listener.onEntityDeath(event);

        assertEquals(Optional.empty(), BlueprintTag.read(stale));
        assertTrue(custom.getItemMeta().hasDisplayName());
        assertEquals(List.of(new Count("entity_death", "normalized", "minecraft:bread", 2),
                new Count("entity_death", "unresolved_custom_name", "minecraft:bread", 1)), service.conversionLog().drain());
    }

    @Test
    void fishing_countsTheCaughtItem_only() {
        PlayerFishEvent caught = mock(PlayerFishEvent.class);
        when(caught.getState()).thenReturn(PlayerFishEvent.State.CAUGHT_FISH);
        when(caught.getCaught()).thenReturn(drop(new ItemStack(Material.COD)));
        PlayerFishEvent reeled = mock(PlayerFishEvent.class);
        when(reeled.getState()).thenReturn(PlayerFishEvent.State.REEL_IN);

        listener.onFish(caught);
        listener.onFish(reeled);

        assertEquals(List.of(new Count("fishing", "vanilla_match", "minecraft:cod", 1)), service.conversionLog().drain());
    }

    @Test
    void craft_normalisesThePreview_withoutCounting_andCountsTheCraft() {
        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(taggedPlain(Material.BREAD, 1, 20));
        PrepareItemCraftEvent prepare = mock(PrepareItemCraftEvent.class);
        when(prepare.getInventory()).thenReturn(inventory);

        listener.onPrepareCraft(prepare);

        verify(inventory).setResult(org.mockito.ArgumentMatchers.argThat(i -> BlueprintTag.read(i).isEmpty() && i.getType() == Material.BREAD));
        assertTrue(service.conversionLog().drain().isEmpty());

        CraftingInventory crafted = mock(CraftingInventory.class);
        when(crafted.getResult()).thenReturn(new ItemStack(Material.BREAD));
        CraftItemEvent craft = mock(CraftItemEvent.class);
        when(craft.getInventory()).thenReturn(crafted);
        listener.onCraft(craft);
        assertEquals(List.of(new Count("craft", "vanilla_match", "minecraft:bread", 1)), service.conversionLog().drain());
    }

    @Test
    void craft_aPlainPreviewIsNotTouched() {
        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getResult()).thenReturn(new ItemStack(Material.BREAD));
        PrepareItemCraftEvent prepare = mock(PrepareItemCraftEvent.class);
        when(prepare.getInventory()).thenReturn(inventory);

        listener.onPrepareCraft(prepare);

        verify(inventory, never()).setResult(any());
    }

    @Test
    void trades_areCounted() {
        PlayerPurchaseEvent event = mock(PlayerPurchaseEvent.class);
        when(event.getTrade()).thenReturn(new MerchantRecipe(new ItemStack(Material.EMERALD, 3), 1));

        listener.onTrade(event);

        assertEquals(List.of(new Count("trade", "vanilla_match", "minecraft:emerald", 3)), service.conversionLog().drain());
    }

    @Test
    void containerOpen_scansTheFirstOpenOnly_andWritesBackChangedSlots() {
        ItemStack[] contents = { new ItemStack(Material.OAK_LOG, 4), null, taggedPlain(Material.BREAD, 3, 20) };
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder(false)).thenReturn(mock(Container.class));
        when(inventory.getLocation()).thenReturn(new Location(world, 5, 64, 5));
        when(inventory.getContents()).thenReturn(contents);
        InventoryOpenEvent event = mock(InventoryOpenEvent.class);
        when(event.getInventory()).thenReturn(inventory);

        listener.onContainerOpen(event);
        listener.onContainerOpen(event);

        verify(inventory).setItem(org.mockito.ArgumentMatchers.eq(2), any());
        verify(inventory, never()).setItem(org.mockito.ArgumentMatchers.eq(0), any());
        assertEquals(1, listener.rememberedContainers());
        assertEquals(List.of(new Count("container_open", "normalized", "minecraft:bread", 3),
                new Count("container_open", "vanilla_match", "minecraft:oak_log", 4)), service.conversionLog().drain());
    }

    @Test
    void containerOpen_ignoresInventoriesThatAreNotContainerBlocks() {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder(false)).thenReturn(player);
        InventoryOpenEvent event = mock(InventoryOpenEvent.class);
        when(event.getInventory()).thenReturn(inventory);

        listener.onContainerOpen(event);

        verify(inventory, never()).getContents();
        verify(inventory, never()).setItem(anyInt(), any());
    }
}
