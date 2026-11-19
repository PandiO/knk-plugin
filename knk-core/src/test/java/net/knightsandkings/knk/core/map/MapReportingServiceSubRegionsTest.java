package net.knightsandkings.knk.core.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

/**
 * Overview map F1 (PandiO/knk-workspace#750): the domain sub-regions an access sync lists are reported too, so a
 * domain's outline is its main region plus its sub-regions.
 */
@SuppressWarnings("unchecked")
class MapReportingServiceSubRegionsTest {

    private final Map<String, RegionOutline> world = new HashMap<>();
    private int reads;
    private final RegionOutlinePort port = (worldName, regionId) -> {
        reads++;
        if (worldName != null) {
            return Optional.ofNullable(world.get(worldName + "/" + regionId.toLowerCase()));
        }
        return world.values().stream().filter(o -> o.regionId().equalsIgnoreCase(regionId)).findFirst();
    };
    private final List<Map<String, Object>> sent = new ArrayList<>();
    private boolean connected = true;
    private final MapReportSender sender = (method, argument) -> {
        assertEquals("ReportRegionOutlines", method);
        if (!connected) {
            return false;
        }
        sent.add(argument);
        return true;
    };

    private MapReportingService service(boolean enabled) {
        return new MapReportingService(enabled, port, sender, Logger.getLogger("test"));
    }

    private void region(String worldName, String id, int size) {
        world.put(worldName + "/" + id, new RegionOutline(id, worldName,
                List.of(new OutlinePoint(0, 0), new OutlinePoint(size, 0), new OutlinePoint(size, size), new OutlinePoint(0, size)), 60, 90));
    }

    private static List<Map<String, Object>> outlines(Map<String, Object> message) {
        return (List<Map<String, Object>>) message.get("outlines");
    }

    private List<String> sentIds() {
        return sent.stream().flatMap(m -> outlines(m).stream()).map(o -> (String) o.get("wgRegionId")).toList();
    }

    @Test
    void offByDefault_readsAndSendsNothing() { // flag, smoke § 1.1 step 7
        region("world", "domain_1_sub_1", 10);
        MapReportingService service = service(false);

        MapReportingService.Summary summary = service.reportSubRegions(List.of(new RegionRef("world", "domain_1_sub_1")));
        service.resendAll();

        assertEquals(0, summary.messages());
        assertEquals(0, reads);
        assertTrue(sent.isEmpty());
    }

    @Test
    void firstSync_sendsEverySubRegion_besideTheMainRegions() { // smoke § 1.1 step 8
        region("world", "domain_1", 100);
        region("world", "domain_1_sub_1", 10);
        region("world", "domain_1_sub_2", 20);
        MapReportingService service = service(true);

        service.reportDomainRegions(List.of(new RegionRef("world", "domain_1")));
        MapReportingService.Summary summary = service.reportSubRegions(List.of(
                new RegionRef("world", "domain_1_sub_1"), new RegionRef("world", "domain_1_sub_2")));

        assertEquals(2, summary.outlines());
        assertEquals(List.of("domain_1", "domain_1_sub_1", "domain_1_sub_2"), sentIds());
        Map<String, Object> sub = outlines(sent.get(1)).getFirst();
        assertEquals("world", sub.get("worldName"));
        assertEquals(60, sub.get("minY"));
        assertEquals(90, sub.get("maxY"));
        assertEquals(3, service.knownRegionCount());
    }

    @Test
    void laterSyncs_sendOnlyNewSubRegions_andAQuietSyncReadsNothing() {
        region("world", "domain_1_sub_1", 10);
        MapReportingService service = service(true);
        service.reportSubRegions(List.of(new RegionRef("world", "domain_1_sub_1")));
        sent.clear();
        reads = 0;

        MapReportingService.Summary quiet = service.reportSubRegions(List.of(new RegionRef("world", "DOMAIN_1_SUB_1")));
        assertEquals(0, quiet.messages());
        assertEquals(0, reads);
        assertTrue(sent.isEmpty());

        region("world", "domain_1_sub_2", 20);
        service.reportSubRegions(List.of(new RegionRef("world", "domain_1_sub_1"), new RegionRef("world", "domain_1_sub_2")));
        assertEquals(List.of("domain_1_sub_2"), sentIds());
        assertEquals(1, reads);
    }

    @Test
    void aMissingSubRegion_isCountedAndTriedAgainOnTheNextSync() {
        MapReportingService service = service(true);

        MapReportingService.Summary first = service.reportSubRegions(List.of(new RegionRef("world", "domain_1_sub_1")));
        assertEquals(1, first.missing());
        assertTrue(sent.isEmpty());

        region("world", "domain_1_sub_1", 10);
        service.reportSubRegions(List.of(new RegionRef("world", "domain_1_sub_1")));
        assertEquals(List.of("domain_1_sub_1"), sentIds());
    }

    @Test
    void theRepair_doesNotForgetTheSubRegions_andTheReconnectResendsBoth() {
        region("world", "domain_1", 100);
        region("world", "domain_1_sub_1", 10);
        MapReportingService service = service(true);
        service.reportSubRegions(List.of(new RegionRef("world", "domain_1_sub_1")));
        service.reportDomainRegions(List.of(new RegionRef("world", "domain_1"))); // repair after the sync
        sent.clear();

        region("world", "domain_1_sub_1", 40); // changed meanwhile
        MapReportingService.Summary summary = service.resendAll();

        assertEquals(2, summary.outlines());
        assertEquals(List.of("domain_1", "domain_1_sub_1"), sentIds());
        assertEquals(40, ((List<Map<String, Object>>) outlines(sent.get(1)).getFirst().get("points")).get(1).get("x"));
    }

    @Test
    void notConnected_theReconnectSendsTheSubRegions() {
        region("world", "domain_1_sub_1", 10);
        MapReportingService service = service(true);
        connected = false;

        MapReportingService.Summary summary = service.reportSubRegions(List.of(new RegionRef("world", "domain_1_sub_1")));
        assertFalse(summary.allSent());

        connected = true;
        service.resendAll();
        assertEquals(List.of("domain_1_sub_1"), sentIds());
    }

    @Test
    void aSubRegionNoLongerListed_leavesTheResendSet_withoutARemoval() {
        region("world", "domain_1_sub_1", 10);
        region("world", "domain_1_sub_2", 10);
        MapReportingService service = service(true);
        service.reportSubRegions(List.of(new RegionRef("world", "domain_1_sub_1"), new RegionRef("world", "domain_1_sub_2")));
        sent.clear();

        service.reportSubRegions(List.of(new RegionRef("world", "domain_1_sub_1")));
        assertTrue(sent.isEmpty());

        service.resendAll();
        assertEquals(List.of("domain_1_sub_1"), sentIds());
        assertTrue(sent.stream().allMatch(m -> ((List<?>) m.get("removed")).isEmpty()));
    }

    @Test
    void aRenamedSubRegion_isNotSentTwice() {
        region("world", "domain_1_sub_1", 10);
        MapReportingService service = service(true);
        service.regionRenamed("world", "tempregion_worldtask_5", "domain_1_sub_1", null);
        sent.clear();
        reads = 0;

        service.reportSubRegions(List.of(new RegionRef("world", "domain_1_sub_1")));
        assertTrue(sent.isEmpty());
        assertEquals(0, reads);

        service.resendAll();
        assertEquals(List.of("domain_1_sub_1"), sentIds());
        assertEquals(1, service.knownRegionCount());
    }
}
