package net.knightsandkings.knk.paper.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.map.OutlinePoint;
import net.knightsandkings.knk.core.map.RegionOutline;

/**
 * Overview map L1 (PandiO/knk-workspace#735, OVERVIEW_MAP_DESIGN § 8): WorldGuard regions become outlines (real
 * WorldGuard region types around mocked region managers, as in {@code WorldGuardRegionRenamerTest}).
 */
class RegionOutlineAdapterTest {

    private static RegionManager manager(String worldName, ProtectedRegion... regions) {
        RegionManager manager = mock(RegionManager.class);
        when(manager.getName()).thenReturn(worldName);
        Map<String, ProtectedRegion> byId = new java.util.HashMap<>();
        for (ProtectedRegion region : regions) {
            byId.put(region.getId(), region);
        }
        when(manager.getRegion(anyString())).thenAnswer(call -> byId.get(((String) call.getArgument(0)).toLowerCase(Locale.ROOT)));
        return manager;
    }

    private static final ProtectedRegion TOWN = new ProtectedCuboidRegion("domain_1",
            BlockVector3.at(-10, 40, 5), BlockVector3.at(20, 120, 35));
    private static final ProtectedRegion DISTRICT = new ProtectedPolygonalRegion("domain_2",
            List.of(BlockVector2.at(0, 0), BlockVector2.at(8, 0), BlockVector2.at(8, 6), BlockVector2.at(3, 9)), 62, 80);

    @Test
    void cuboid_isItsFourCornersAndYRange() {
        RegionOutline outline = new RegionOutlineAdapter(() -> List.of(manager("world", TOWN))).outline("world", "domain_1").orElseThrow();

        assertEquals("domain_1", outline.regionId());
        assertEquals("world", outline.worldName());
        assertEquals(4, outline.points().size());
        assertTrue(outline.points().containsAll(List.of(new OutlinePoint(-10, 5), new OutlinePoint(20, 5),
                new OutlinePoint(20, 35), new OutlinePoint(-10, 35))));
        assertEquals((40), outline.minY());
        assertEquals(120, outline.maxY());
    }

    @Test
    void polygon_isItsPointsInOrder() {
        RegionOutline outline = new RegionOutlineAdapter(() -> List.of(manager("world", DISTRICT))).outline("world", "DOMAIN_2").orElseThrow();

        assertEquals(List.of(new OutlinePoint(0, 0), new OutlinePoint(8, 0), new OutlinePoint(8, 6), new OutlinePoint(3, 9)),
                outline.points());
        assertEquals(62, outline.minY());
        assertEquals(80, outline.maxY());
    }

    @Test
    void aNamedWorldIsTheOnlyOneLookedIn_withoutAWorldTheFirstLoadedWorldThatHasIt() {
        RegionOutlineAdapter adapter = new RegionOutlineAdapter(() -> List.of(manager("hub"), manager("world", TOWN)));

        assertEquals(Optional.empty(), adapter.outline("hub", "domain_1"));
        assertEquals("world", adapter.outline(null, "domain_1").orElseThrow().worldName());
    }

    @Test
    void globalAndMissingRegions_haveNoOutline() {
        RegionOutlineAdapter adapter = new RegionOutlineAdapter(() -> List.of(manager("world", new GlobalProtectedRegion("__global__"))));

        assertEquals(Optional.empty(), adapter.outline("world", "__global__"));
        assertEquals(Optional.empty(), adapter.outline("world", "nope"));
        assertEquals(Optional.empty(), adapter.outline("world", " "));
    }
}
