package net.knightsandkings.knk.core.production.shop.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Display stalls P12 (PandiO/knk-workspace#250): the spots by block, shop and chunk, replaced and changed in place. */
class DisplaySpotIndexTest {

    private static DisplaySpot spot(int id, int shop, int x, int z, int order) {
        return new DisplaySpot(id, shop, "world", x, 64, z, null, null, order, null, true);
    }

    @Test
    void findsSpotsByBlockShopAndChunk() {
        DisplaySpotIndex index = new DisplaySpotIndex();
        index.replace(List.of(spot(1, 5, 1, 1, 2), spot(2, 5, 3, 3, 1), spot(3, 6, 40, -20, 1)));
        assertEquals(3, index.size());
        assertEquals(1, index.at("world", 1, 64, 1).orElseThrow().id());
        assertTrue(index.at("world", 1, 65, 1).isEmpty());
        assertEquals(List.of(2, 1), index.ofShop(5).stream().map(DisplaySpot::id).toList());
        assertEquals(List.of(5), index.shopsInChunk("world", 0, 0));
        assertEquals(List.of(6), index.shopsInChunk("world", 2, -2));
        assertEquals(List.of(5, 6), index.shops());
        assertEquals(DisplaySpot.Facing.SOUTH, index.byId(1).orElseThrow().facing());
    }

    @Test
    void aMovedSpotLeavesItsOldBlock_andRemovedSpotsAreGone() {
        DisplaySpotIndex index = new DisplaySpotIndex();
        index.replace(List.of(spot(1, 5, 1, 1, 1)));
        index.put(spot(1, 5, 9, 9, 1));
        assertTrue(index.at("world", 1, 64, 1).isEmpty());
        assertEquals(1, index.at("world", 9, 64, 9).orElseThrow().id());
        assertEquals(1, index.remove(1).orElseThrow().id());
        assertTrue(index.at("world", 9, 64, 9).isEmpty());
        assertTrue(index.remove(1).isEmpty());
        index.replace(null);
        assertEquals(0, index.size());
    }
}
