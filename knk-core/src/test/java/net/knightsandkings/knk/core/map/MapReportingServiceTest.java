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
 * Overview map L1 (PandiO/knk-workspace#735, OVERVIEW_MAP_DESIGN § 7.1, A01, A02, O27): what the plugin sends over the
 * game hub for the domain regions.
 */
@SuppressWarnings("unchecked")
class MapReportingServiceTest {

    /** Regions by "world/id"; reads count. */
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> outlines(Map<String, Object> message) {
        return (List<Map<String, Object>>) message.get("outlines");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> removed(Map<String, Object> message) {
        return (List<Map<String, Object>>) message.get("removed");
    }

    @Test
    void offByDefault_readsAndSendsNothing() { // O27, smoke § 1.1 step 5
        region("world", "domain_1", 10);
        MapReportingService service = service(false);

        service.reportDomainRegions(List.of(new RegionRef("world", "domain_1")));
        service.regionRenamed("world", "tempregion_worldtask_1", "domain_1", null);
        service.resendAll();

        assertEquals(0, reads);
        assertTrue(sent.isEmpty());
        assertFalse(service.enabled());
    }

    @Test
    void startup_sendsEveryDomainRegionWithPointsAndYRange() { // A01
        region("world", "domain_1", 100);
        region("world", "domain_2", 10);
        region("hub", "domain_9", 5);

        MapReportingService.Summary summary = service(true).reportDomainRegions(List.of(
                new RegionRef("world", "domain_1"), new RegionRef("world", "domain_2"), new RegionRef(null, "domain_9"),
                new RegionRef("world", "stale_region")));

        assertEquals(3, summary.outlines());
        assertEquals(1, summary.missing());
        Map<String, Object> message = sent.getFirst();
        assertEquals(3, outlines(message).size());
        Map<String, Object> first = outlines(message).getFirst();
        assertEquals("domain_1", first.get("wgRegionId"));
        assertEquals("world", first.get("worldName"));
        assertEquals(60, first.get("minY"));
        assertEquals(90, first.get("maxY"));
        assertEquals(List.of(Map.of("x", 0, "z", 0), Map.of("x", 100, "z", 0), Map.of("x", 100, "z", 100), Map.of("x", 0, "z", 100)),
                first.get("points"));
        // a world-less domain is reported with the world its region was found in
        assertEquals("hub", outlines(message).get(2).get("worldName"));
        assertTrue(removed(message).isEmpty());
    }

    @Test
    void aRegionClaimedTwiceIsSentOnce() {
        region("world", "domain_1", 10);

        service(true).reportDomainRegions(List.of(new RegionRef("world", "domain_1"), new RegionRef("world", "DOMAIN_1")));

        assertEquals(1, outlines(sent.getFirst()).size());
    }

    @Test
    void rename_sendsTheNewOutlineAndRemovesTheOldNames() { // A02, smoke § 1.1 step 4
        region("world", "domain_2", 20);
        MapReportingService service = service(true);

        service.regionRenamed("world", "tempregion_worldtask_7", "domain_2", "tempregion_worldtask_3");

        Map<String, Object> message = sent.getFirst();
        assertEquals("domain_2", outlines(message).getFirst().get("wgRegionId"));
        assertEquals(List.of(Map.of("wgRegionId", "tempregion_worldtask_7", "worldName", "world"),
                Map.of("wgRegionId", "tempregion_worldtask_3", "worldName", "world")), removed(message));
        assertEquals(1, service.knownRegionCount());
    }

    @Test
    void reRunOfARegionStep_replacesInPlace_andNeverRemovesTheFinalName() { // A02, smoke § 1.1 step 3 (KNG-103)
        region("world", "domain_2", 30);

        service(true).regionRenamed("world", "tempregion_worldtask_8", "domain_2", "domain_2");

        Map<String, Object> message = sent.getFirst();
        assertEquals(30, ((List<Map<String, Object>>) outlines(message).getFirst().get("points")).get(1).get("x"));
        assertEquals(List.of(Map.of("wgRegionId", "tempregion_worldtask_8", "worldName", "world")), removed(message));
    }

    @Test
    void notConnected_isReportedAndTheReconnectResendsTheCurrentOutlines() {
        region("world", "domain_1", 10);
        MapReportingService service = service(true);
        connected = false;

        MapReportingService.Summary summary = service.reportDomainRegions(List.of(new RegionRef("world", "domain_1")));
        assertFalse(summary.allSent());
        assertTrue(sent.isEmpty());

        connected = true;
        region("world", "domain_1", 40); // changed meanwhile
        service.resendAll();

        assertEquals(40, ((List<Map<String, Object>>) outlines(sent.getFirst()).getFirst().get("points")).get(1).get("x"));
    }

    @Test
    void resendAll_beforeTheFirstRepair_sendsNothing() {
        service(true).resendAll();

        assertTrue(sent.isEmpty());
        assertEquals(0, reads);
    }

    @Test
    void manyRegions_areSplitIntoMessagesUnderTheHubLimit() {
        List<RegionRef> refs = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            region("world", "domain_" + i, i + 1);
            refs.add(new RegionRef("world", "domain_" + i));
        }

        MapReportingService.Summary summary = service(true).reportDomainRegions(refs);

        assertEquals(300, summary.outlines());
        assertTrue(sent.size() > 1, "split into several messages");
        assertEquals(300, sent.stream().mapToInt(m -> outlines(m).size()).sum());
        for (Map<String, Object> message : sent) {
            // the API's SignalR receive limit is 32 KB; the message text stays well under it
            assertTrue(message.toString().length() < MapReportingService.MAX_MESSAGE_CHARS, message.toString().length() + " chars");
        }
    }

    @Test
    void anOutlineTooLargeForOneMessage_isSkipped() {
        List<OutlinePoint> points = new ArrayList<>();
        for (int i = 0; i <= MapReportingService.MAX_POINTS; i++) {
            points.add(new OutlinePoint(i, i % 2));
        }
        world.put("world/huge", new RegionOutline("huge", "world", points, 0, 10));
        region("world", "domain_1", 10);

        MapReportingService.Summary summary = service(true).reportDomainRegions(
                List.of(new RegionRef("world", "huge"), new RegionRef("world", "domain_1")));

        assertEquals((1), summary.skipped());
        assertEquals(1, outlines(sent.getFirst()).size());
    }

    @Test
    void aFailingRegionRead_countsAsMissing() {
        MapReportingService service = new MapReportingService(true, (w, id) -> {
            throw new IllegalStateException("WorldGuard not ready");
        }, sender, Logger.getLogger("test"));

        assertEquals(1, service.reportDomainRegions(List.of(new RegionRef("world", "domain_1"))).missing());
        assertTrue(sent.isEmpty());
    }
}
