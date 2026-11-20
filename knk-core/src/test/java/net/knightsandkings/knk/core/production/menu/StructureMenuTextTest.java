package net.knightsandkings.knk.core.production.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** PandiO/knk-workspace#74: the structure menus' text (M1-M5) and the overview's facets (M2). */
class StructureMenuTextTest {

    static final StructureRow QUARRY = new StructureRow(5, "ProductionStructure", "Quarry", 3, "Quarry", 2, "Phoenix",
            true, 1, List.of("Cobblestone"), "NoRoad", "Not connected to a road");
    static final StructureRow NORTH = new StructureRow(9, "Warehouse", "North", null, "Warehouse", 2, "Phoenix", false,
            null, List.of(), "Working", "");
    static final StructureRow FARM = new StructureRow(6, "ProductionStructure", "Wheat field", 1, "Farm", 4,
            "River, town", false, 2, List.of("Wheat"), "Working", "");

    @Test
    void publicLines_showKindTownLevelProductsAndStatus() {
        assertEquals(List.of("&7Kind: &fQuarry", "&7Town: &fPhoenix &8(rural)", "&7Level: &f1",
                "&7Produces: &fCobblestone", "&7Status: &cNo road &8- &7Not connected to a road"),
                StructureMenuText.summaryLines(QUARRY));
        assertEquals("&7Kind: &fWarehouse", StructureMenuText.summaryLines(NORTH).get(0));
        assertEquals("COBBLESTONE", StructureMenuText.material(QUARRY));
        assertEquals("CHEST", StructureMenuText.material(NORTH));
        assertEquals("WHEAT", StructureMenuText.material(FARM));
        assertEquals("&a", StructureMenuText.statusColour("Working"));
        assertEquals("&6", StructureMenuText.statusColour("Stalled"));
    }

    @Test
    void detailLines_areEmptyWithoutTheDetail_andFullWithIt() {
        StructureMenu visitor = new StructureMenu(QUARRY, false, false, null);
        assertTrue(StructureMenuText.rateLines(visitor).isEmpty());
        assertTrue(StructureMenuText.workerLines(visitor).isEmpty());
        assertTrue(StructureMenuText.warningLines(visitor).isEmpty());
        assertEquals("", StructureMenuText.ordersLine(visitor));
        // the API said details, but sent none: still nothing shown
        assertFalse(new StructureMenu(QUARRY, true, false, null).showsDetail());

        StructureMenu member = new StructureMenu(QUARRY, true, false, new StructureMenu.Detail(2, 3, 1,
                List.of(new StructureMenu.Rate(1, 3, "Cobblestone", 12.5, "Running", List.of()),
                        new StructureMenu.Rate(2, 4, "Coal", 0, "Blocked", List.of("Output full"))),
                List.of(), List.of("Output is 90% full"), List.of("No road"), 1));
        assertEquals(List.of("&fCobblestone&7: &e12.5 per hour", "&fCoal&7: &e0 per hour &8(Blocked)", "  &cOutput full"),
                StructureMenuText.rateLines(member));
        assertEquals(List.of("&7Present: &f2 &7of &f3", "&7Away: &f1"), StructureMenuText.workerLines(member));
        assertEquals(List.of("&cOutput is 90% full", "&6No road"), StructureMenuText.warningLines(member));
        assertEquals("&71 open order", StructureMenuText.ordersLine(member));
    }

    @Test
    void storageLines_followV2StorageOverview() {
        StructureMenu.StorageView output = new StructureMenu.StorageView(8, "Output", "Output", 40, 1000L,
                List.of(new StructureMenu.Line(3, "Cobblestone", 40, null, null)));
        assertEquals("&7Filled: &f40 &7of &f1,000 &7units (4%)", StructureMenuText.fillLine(output));
        assertEquals(List.of("&fCobblestone&7: Amount: &f40"), StructureMenuText.contentLines(output));
        StructureMenu.StorageView empty = new StructureMenu.StorageView(8, "Output", "Output", 0, null, List.of());
        assertEquals("&7Holds &f0 &7units", StructureMenuText.fillLine(empty));
        assertEquals(List.of("&7Empty."), StructureMenuText.contentLines(empty));
        List<StructureMenu.Line> many = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            many.add(new StructureMenu.Line(i, "Item " + i, 1, null, null));
        }
        List<String> lines = StructureMenuText.contentLines(new StructureMenu.StorageView(8, "Big", "Output", 15, null, many));
        assertEquals(StructureMenuText.MAX_CONTENT_LINES + 1, lines.size());
        assertEquals("&8... and 3 more", lines.get(lines.size() - 1));
    }

    @Test
    void orders_showCargoFromToStateAndArrival_andTheStaffRules() {
        Instant now = Instant.parse("2026-11-20T10:00:00Z");
        TransportOrderRow moving = order("InTransit", now.plusSeconds(240), null);
        assertEquals(List.of("&7Cargo: &f10 &7units", "&7From: &fQuarry", "&7To: &fNorth delivery", "&7Arrives: &fin 4 min",
                "&8Manual"), StructureMenuText.orderLines(moving, now));
        assertEquals("MINECART", StructureMenuText.orderMaterial(moving));
        assertTrue(moving.canCancel());
        assertFalse(moving.canRetry());
        assertEquals("&eStaff: click to cancel", StructureMenuText.staffHint(moving));

        TransportOrderRow waiting = order("Planned", null, null);
        assertTrue(waiting.canCancel());
        assertTrue(waiting.canRetry());
        TransportOrderRow cancelled = order("Cancelled", null, now.minusSeconds(7200));
        assertFalse(cancelled.canCancel());
        assertTrue(cancelled.canRetry());
        assertEquals("&7Finished: &f2 h ago", StructureMenuText.orderLines(cancelled, now).get(3));
        TransportOrderRow delivered = order("Delivered", null, now);
        assertFalse(delivered.canCancel());
        assertFalse(delivered.canRetry());
        assertEquals("", StructureMenuText.staffHint(delivered));
        assertFalse(order("Returning", null, null).canCancel());
    }

    @Test
    void numbers() {
        assertEquals("12.5", StructureMenuText.perHour(12.5));
        assertEquals("3", StructureMenuText.perHour(3));
        assertEquals("0.25", StructureMenuText.perHour(0.25));
        assertEquals("now", StructureMenuText.relative(Instant.EPOCH, Instant.EPOCH.plusSeconds(30)));
        assertEquals("in 2 d", StructureMenuText.relative(Instant.EPOCH.plusSeconds(200_000), Instant.EPOCH));
    }

    @Test
    void facets_cycleByName_andTheApiFiltersById() {
        StructureFacets facets = StructureFacets.of(List.of(QUARRY, NORTH, FARM));

        assertEquals("Phoenix,River town", facets.townValues());
        assertEquals("Farm,Quarry", facets.kindValues());
        assertEquals("Phoenix", facets.townValue(2));
        assertEquals("77", facets.townValue(77));
        assertEquals(Map.of("townId", "2", "productionKindId", "1", "status", "Full"),
                facets.toApiFilters(Map.of("Town", "phoenix", "Kind", "Farm", "Status", "Full")));
        assertEquals(Map.of("townId", "77"), facets.toApiFilters(Map.of("Town", "77")));
        assertEquals(Map.of("townId", "-1"), facets.toApiFilters(Map.of("Town", "Atlantis")));
        assertTrue(StructureFacets.EMPTY.toApiFilters(Map.of("Town", " ")).isEmpty());
        assertEquals("&7Town: &fall", StructureFacets.filterLine("Town", null));
        assertEquals("&7Status: &fFull", StructureFacets.filterLine("Status", "Full"));
    }

    private static TransportOrderRow order(String status, Instant eta, Instant completed) {
        return new TransportOrderRow(31, "Manual", status, null, "Quarry", "Output", "North delivery", 10, 0, 0, 0,
                Instant.EPOCH, eta, completed);
    }
}
