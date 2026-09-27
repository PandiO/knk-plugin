package net.knightsandkings.knk.paper.mapper;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Lootboxes Phase 5: the {@code knightsandkings:knk_lootbox_token} tag is a token item's only identity. */
public class LootboxTokenTagTest {

    /** An item whose meta keeps STRING values in a map, like a real PDC would; {@code amount} is its stack size. */
    public static ItemStack itemWithPdc(int amount) {
        Map<Object, Object> values = new HashMap<>();
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        doAnswer(inv -> values.put(inv.getArgument(0), inv.getArgument(2)))
                .when(pdc).set(any(), eq(PersistentDataType.STRING), any());
        when(pdc.get(any(), eq(PersistentDataType.STRING))).thenAnswer(inv -> values.get(inv.getArgument(0)));
        ItemMeta meta = mock(ItemMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        ItemStack item = mock(ItemStack.class);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(item.getAmount()).thenReturn(amount);
        return item;
    }

    public static ItemStack token(UUID token, int amount) {
        ItemStack item = itemWithPdc(amount);
        LootboxTokenTag.stamp(item.getItemMeta(), token);
        return item;
    }

    @Test
    void stampThenRead_returnsTheToken() {
        UUID token = UUID.randomUUID();
        ItemStack box = token(token, 1);

        assertEquals(Optional.of(token), LootboxTokenTag.read(box));
        assertTrue(LootboxTokenTag.isToken(box));
        assertTrue(LootboxTokenTag.contains(new ItemStack[]{null, box}, token));
        assertFalse(LootboxTokenTag.contains(new ItemStack[]{box}, UUID.randomUUID()));
    }

    @Test
    void aRenamedItem_withoutTheTag_isNotAToken() {
        // v1's exploit: any item renamed "Rare Sword Box" in an anvil. The name is never read.
        ItemStack renamed = itemWithPdc(1);
        when(renamed.getItemMeta().getDisplayName()).thenReturn("Rare Sword Box");

        assertFalse(LootboxTokenTag.isToken(renamed));
        assertFalse(LootboxTokenTag.isToken(null));
    }

    @Test
    void aMalformedTag_isNotAToken() {
        ItemStack forged = itemWithPdc(1);
        forged.getItemMeta().getPersistentDataContainer().set(LootboxTokenTag.TOKEN_KEY, PersistentDataType.STRING, "rare sword box");

        assertFalse(LootboxTokenTag.isToken(forged));
    }
}
