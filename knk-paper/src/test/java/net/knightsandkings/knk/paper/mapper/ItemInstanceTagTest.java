package net.knightsandkings.knk.paper.mapper;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Lootboxes Phase 3: the {@code knightsandkings:knk_item_instance} tag round trip. */
public class ItemInstanceTagTest {

    /** An item whose meta keeps LONG values in a map, like a real PDC would. */
    public static ItemStack itemWithPdc() {
        Map<Object, Object> values = new HashMap<>();
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        doAnswer(inv -> values.put(inv.getArgument(0), inv.getArgument(2)))
                .when(pdc).set(any(), eq(PersistentDataType.LONG), any());
        when(pdc.get(any(), eq(PersistentDataType.LONG))).thenAnswer(inv -> values.get(inv.getArgument(0)));
        ItemMeta meta = mock(ItemMeta.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        ItemStack item = mock(ItemStack.class);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        return item;
    }

    @Test
    void stampThenRead_returnsTheId() {
        ItemStack sword = itemWithPdc();

        ItemInstanceTag.stamp(sword.getItemMeta(), 9_007_199_254_740_993L);

        assertEquals(Optional.of(9_007_199_254_740_993L), ItemInstanceTag.read(sword));
        assertTrue(ItemInstanceTag.containsInstance(new ItemStack[]{null, sword}, 9_007_199_254_740_993L));
        assertFalse(ItemInstanceTag.containsInstance(new ItemStack[]{sword}, 1L));
    }

    @Test
    void missingTag_orNoMeta_isEmpty() {
        assertEquals(Optional.empty(), ItemInstanceTag.read(itemWithPdc()));
        ItemStack plain = mock(ItemStack.class);
        when(plain.hasItemMeta()).thenReturn(false);
        assertEquals(Optional.empty(), ItemInstanceTag.read(plain));
        assertEquals(Optional.empty(), ItemInstanceTag.read((ItemStack) null));
        assertFalse(ItemInstanceTag.containsInstance(null, 1L));
    }

    @Test
    void nullId_stampsNothing() {
        ItemStack bread = itemWithPdc();

        ItemInstanceTag.stamp(bread.getItemMeta(), null);

        assertEquals(Optional.empty(), ItemInstanceTag.read(bread));
    }
}
