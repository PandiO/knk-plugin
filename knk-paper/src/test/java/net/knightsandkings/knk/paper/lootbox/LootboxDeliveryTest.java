package net.knightsandkings.knk.paper.lootbox;

import net.knightsandkings.knk.core.domain.enchantments.KnkEnchantmentDefinition;
import net.knightsandkings.knk.core.domain.item.KnkGrade;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprint;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprintDefaultEnchantment;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimEnchantment;
import net.knightsandkings.knk.core.lootbox.KnkLootboxClaimResult;
import net.knightsandkings.knk.core.lootbox.LootboxDeliveryMethod;
import net.knightsandkings.knk.paper.item.BlueprintItemAssembler;
import net.knightsandkings.knk.paper.mapper.ItemInstanceTag;
import net.knightsandkings.knk.paper.mapper.ItemInstanceTagTest;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lootboxes Phase 3: full inventory refused before any claim, leftovers dropped owner-locked, the instance id stamped
 * on a sword and not on a food stack, the claim's enchantments split into blueprint defaults (as authored) and rolled
 * ones (vanilla rules), and the claim's grade on the item.
 */
class LootboxDeliveryTest {

    private final UUID owner = UUID.randomUUID();

    static KnkLootboxClaimResult claim(Long instanceId, int quantity, List<KnkLootboxClaimEnchantment> enchantments,
                                       Integer gradeId, Integer gradeStars) {
        return new KnkLootboxClaimResult(41, false, 9, 12, 3, 5, "Legendary Weapons Lootbox", instanceId, 77, "Steel Sword",
                gradeId, gradeStars, quantity, false, enchantments, false, null, null);
    }

    static KnkItemBlueprint blueprint(KnkGrade grade, KnkItemBlueprintDefaultEnchantment... defaults) {
        return new KnkItemBlueprint(77, "Steel Sword", null, 5, "minecraft:iron_sword", "&bSteel Sword", null, 1, 1,
                List.of(defaults), defaults.length, grade, List.of(), List.of());
    }

    private Player playerWithInventory(PlayerInventory inventory) {
        Player player = mock(Player.class);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getUniqueId()).thenReturn(owner);
        return player;
    }

    @Test
    void fullInventory_hasNoRoom() {
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.firstEmpty()).thenReturn(-1);
        assertFalse(LootboxDelivery.hasRoom(playerWithInventory(inventory)));

        when(inventory.firstEmpty()).thenReturn(7);
        assertTrue(LootboxDelivery.hasRoom(playerWithInventory(inventory)));
    }

    @Test
    void everythingFits_isAnInventoryDelivery() {
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack sword = mock(ItemStack.class);
        when(inventory.addItem(sword)).thenReturn(new HashMap<>());
        Player player = playerWithInventory(inventory);

        assertEquals(LootboxDeliveryMethod.INVENTORY, LootboxDelivery.place(player, sword, LootboxDeliveryMethod.INVENTORY));
        verify(player, never()).getWorld();
    }

    @Test
    void leftovers_dropOwnerLocked() {
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack stack = mock(ItemStack.class);
        ItemStack rest = mock(ItemStack.class);
        when(inventory.addItem(stack)).thenReturn(new HashMap<>(Map.of(0, rest)));
        World world = mock(World.class);
        Item dropped = mock(Item.class);
        Location feet = new Location(world, 1, 64, 1);
        when(world.dropItem(feet, rest)).thenReturn(dropped);
        Player player = playerWithInventory(inventory);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(feet);

        assertEquals(LootboxDeliveryMethod.DROPPED_OWNED, LootboxDelivery.place(player, stack, LootboxDeliveryMethod.INVENTORY));
        verify(dropped).setOwner(owner);
        verify(dropped).setCanMobPickup(false);
    }

    @Test
    void instanceIdIsStampedOnASword_butNotOnAFoodStack() {
        ItemStack sword = ItemInstanceTagTest.itemWithPdc();
        LootboxDelivery.instanceStamp(claim(1234L, 1, List.of(), null, null)).accept(sword.getItemMeta());
        assertEquals(Optional.of(1234L), ItemInstanceTag.read(sword));

        ItemStack bread = ItemInstanceTagTest.itemWithPdc();
        LootboxDelivery.instanceStamp(claim(null, 16, List.of(), null, null)).accept(bread.getItemMeta());
        assertEquals(Optional.empty(), ItemInstanceTag.read(bread));
    }

    @Test
    void enchantments_splitIntoBlueprintDefaultsAndRolled() {
        KnkItemBlueprint withSharpness = blueprint(null,
                new KnkItemBlueprintDefaultEnchantment(77, 1, 2, "minecraft:sharpness", "Sharpness", 5, false));
        KnkLootboxClaimResult claim = claim(5L, 1, List.of(
                new KnkLootboxClaimEnchantment(1, "minecraft:sharpness", false, 4), // default, raised by a roll
                new KnkLootboxClaimEnchantment(2, "minecraft:knockback", false, 2),
                new KnkLootboxClaimEnchantment(10, "poison", true, 3)), null, null);

        LootboxDelivery.Requests requests = LootboxDelivery.requests(withSharpness, claim, Map.of());

        assertEquals(List.of(1), requests.defaults().stream().map(BlueprintItemAssembler.EnchantmentRequest::definitionId).toList());
        assertEquals(Integer.valueOf(4), requests.defaults().get(0).level());
        assertEquals(List.of(2, 10), requests.rolled().stream().map(BlueprintItemAssembler.EnchantmentRequest::definitionId).toList());
        KnkEnchantmentDefinition knockback = requests.rolled().get(0).definition();
        assertEquals("minecraft:knockback", knockback.baseEnchantmentNamespaceKey());
        KnkEnchantmentDefinition poison = requests.rolled().get(1).definition();
        assertEquals(Boolean.TRUE, poison.isCustom());
        assertNull(poison.baseEnchantmentNamespaceKey());
        assertEquals(Integer.valueOf(3), poison.maxLevel());
    }

    @Test
    void enchantments_preferAFetchedDefinition() {
        KnkEnchantmentDefinition fetched = new KnkEnchantmentDefinition(3, "knk:keen", "Keen", null, false, 5, null, "minecraft:sharpness");
        KnkLootboxClaimResult claim = claim(5L, 1, List.of(new KnkLootboxClaimEnchantment(3, "knk:keen", false, 2)), null, null);

        LootboxDelivery.Requests requests = LootboxDelivery.requests(blueprint(null), claim, Map.of(3, fetched));

        assertSame(fetched, requests.rolled().get(0).definition());
    }

    @Test
    void theClaimsGradeReplacesTheBlueprintsWhenItDiffers() {
        KnkItemBlueprint ungraded = blueprint(null);
        assertEquals(Integer.valueOf(5), LootboxDelivery.withClaimGrade(ungraded, claim(1L, 1, List.of(), 5, 5)).grade().stars());

        KnkItemBlueprint rare = blueprint(new KnkGrade(3, "Rare", 3));
        assertSame(rare, LootboxDelivery.withClaimGrade(rare, claim(1L, 1, List.of(), 3, 3)));
        assertSame(rare, LootboxDelivery.withClaimGrade(rare, claim(1L, 1, List.of(), null, null)));
        KnkItemBlueprint overridden = LootboxDelivery.withClaimGrade(rare, claim(1L, 1, List.of(), 4, 4));
        assertEquals(Integer.valueOf(4), overridden.grade().stars());
        assertEquals(rare.defaultEnchantments(), overridden.defaultEnchantments());
    }

    @Test
    void redelivery_findsAnInstanceAlreadyHeld() {
        ItemStack sword = ItemInstanceTagTest.itemWithPdc();
        ItemInstanceTag.stamp(sword.getItemMeta(), 88L);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getContents()).thenReturn(new ItemStack[]{null});
        org.bukkit.inventory.Inventory enderChest = mock(org.bukkit.inventory.Inventory.class);
        when(enderChest.getContents()).thenReturn(new ItemStack[]{sword});
        Player player = playerWithInventory(inventory);
        when(player.getEnderChest()).thenReturn(enderChest);

        assertTrue(LootboxDelivery.holdsInstance(player, 88L));
        assertFalse(LootboxDelivery.holdsInstance(player, 89L));
        verify(inventory, never()).addItem(any(ItemStack.class));
    }
}
