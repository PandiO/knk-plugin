package net.knightsandkings.knk.core.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.users.ActiveMode;

/**
 * Overview map L4 (PandiO/knk-workspace#738, OVERVIEW_MAP_DESIGN § 5 rule 6, § 7.1, O07, O27, A11, A13, A16): the live
 * player report the plugin sends over the game hub ({@code ReportMapPlayers}).
 */
@SuppressWarnings("unchecked")
class MapReportingServicePlayersTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final RegionOutlinePort noOutlines = (world, id) -> Optional.empty();
    private List<OnlinePlayerPosition> online = new ArrayList<>();
    private int snapshots;
    private final OnlinePlayerSnapshotPort port = () -> {
        snapshots++;
        return online;
    };
    private final List<String> methods = new ArrayList<>();
    private final List<Map<String, Object>> sent = new ArrayList<>();
    private boolean connected = true;
    private final MapReportSender sender = (method, argument) -> {
        if (!connected) {
            return false;
        }
        methods.add(method);
        sent.add(argument);
        return true;
    };

    private MapReportingService service(boolean enabled, int intervalTicks) {
        return new MapReportingService(enabled, noOutlines, port, intervalTicks, sender, Logger.getLogger("test"));
    }

    private static OnlinePlayerPosition at(UUID uuid, double x, boolean afk, ActiveMode mode, String minigame, Long matchId) {
        return new OnlinePlayerPosition(uuid, "world", x, 64.04, -12.36, -91.6f, afk, mode, minigame, matchId);
    }

    private static List<Map<String, Object>> players(Map<String, Object> message) {
        return (List<Map<String, Object>>) message.get("players");
    }

    @Test
    void off_readsAndSendsNothing() {
        online.add(at(A, 1, false, ActiveMode.NONE, null, null));
        MapReportingService service = service(false, 40);

        assertFalse(service.playersEnabled());
        assertNull(service.collectPlayers());
        assertFalse(service.sendPlayers(new MapReportingService.PlayersReport(1, List.of(Map.of()))));
        assertEquals(0, snapshots);
        assertTrue(sent.isEmpty());
    }

    @Test
    void withoutPlayerSource_outlinesOnlyConstructorHasNoPlayerReport() {
        MapReportingService service = new MapReportingService(true, noOutlines, sender, Logger.getLogger("test"));

        assertFalse(service.playersEnabled());
        assertNull(service.collectPlayers());
        assertEquals(MapReportingService.DEFAULT_INTERVAL_TICKS, service.intervalTicks());
    }

    @Test
    void report_carriesEveryFieldOfSpecRule6_rounded() {
        online.add(at(A, 10.04, true, ActiveMode.STAFF, "siege", 77L));
        MapReportingService service = service(true, 40);

        MapReportingService.PlayersReport report = service.collectPlayers();
        assertTrue(service.sendPlayers(report));

        assertEquals(List.of("ReportMapPlayers"), methods);
        Map<String, Object> message = sent.get(0);
        assertEquals(2000L, message.get("intervalMs"));
        Map<String, Object> p = players(message).get(0);
        assertEquals(List.of("uuid", "world", "x", "y", "z", "yaw", "afk", "mode", "minigame", "matchId"), List.copyOf(p.keySet()));
        assertEquals(A.toString(), p.get("uuid"));
        assertEquals("world", p.get("world"));
        assertEquals(10.0, p.get("x"));
        assertEquals(64.0, p.get("y"));
        assertEquals(-12.4, p.get("z"));
        assertEquals(-92, p.get("yaw"));
        assertEquals(true, p.get("afk"));
        assertEquals("staff", p.get("mode"));
        assertEquals("siege", p.get("minigame"));
        assertEquals(77L, p.get("matchId"));
    }

    @Test
    void modes_useTheApiWireValues() {
        online.add(at(A, 0, false, ActiveMode.NONE, null, null));
        online.add(at(B, 0, false, ActiveMode.OWNER, null, null));
        MapReportingService service = service(true, 40);

        service.sendPlayers(service.collectPlayers());

        List<Map<String, Object>> list = players(sent.get(0));
        assertEquals("none", list.get(0).get("mode"));
        assertEquals("owner", list.get(1).get("mode"));
        assertNull(list.get(0).get("minigame"));
        assertNull(list.get(0).get("matchId"));
    }

    @Test
    void movement_isSeenInTheNextReport() {
        online.add(at(A, 0, false, ActiveMode.NONE, null, null));
        MapReportingService service = service(true, 40);
        service.sendPlayers(service.collectPlayers());

        online = new ArrayList<>(List.of(at(A, 20, false, ActiveMode.NONE, null, null)));
        service.sendPlayers(service.collectPlayers());

        assertEquals(2, sent.size());
        assertEquals(20.0, players(sent.get(1)).get(0).get("x"));
    }

    @Test
    void emptyServer_sendsOneEmptyReportThenNothingUntilSomeoneJoins() {
        MapReportingService service = service(true, 40);

        MapReportingService.PlayersReport first = service.collectPlayers();
        assertTrue(service.sendPlayers(first));
        assertTrue(players(sent.get(0)).isEmpty());

        assertNull(service.collectPlayers());
        assertEquals(1, sent.size());

        online.add(at(A, 0, false, ActiveMode.NONE, null, null));
        service.sendPlayers(service.collectPlayers());
        assertEquals(2, sent.size());
    }

    @Test
    void failedEmptyReport_isTriedAgainNextInterval() {
        MapReportingService service = service(true, 40);
        connected = false;
        assertFalse(service.sendPlayers(service.collectPlayers()));

        connected = true;
        MapReportingService.PlayersReport again = service.collectPlayers();
        assertTrue(again != null && service.sendPlayers(again));
        assertEquals(1, sent.size());
    }

    @Test
    void manyPlayers_areSplitBelowTheHubMessageLimit() {
        for (int i = 0; i < 250; i++) {
            online.add(at(new UUID(0, i), i, false, ActiveMode.NONE, null, null));
        }
        MapReportingService service = service(true, 40);

        MapReportingService.PlayersReport report = service.collectPlayers();
        assertTrue(service.sendPlayers(report));

        assertTrue(sent.size() > 1);
        int total = 0;
        for (Map<String, Object> message : sent) {
            total += players(message).size();
            assertEquals(2000L, message.get("intervalMs"));
            assertTrue(players(message).size() * MapReportingService.PLAYER_CHARS <= MapReportingService.MAX_MESSAGE_CHARS);
        }
        assertEquals(250, total);
        assertEquals(250, report.players());
    }

    @Test
    void interval_isClampedAndSentInMilliseconds() {
        assertEquals(10, service(true, 1).intervalTicks());
        assertEquals(1200, service(true, 99_999).intervalTicks());
        online.add(at(A, 0, false, ActiveMode.NONE, null, null));
        MapReportingService service = service(true, 20);
        service.sendPlayers(service.collectPlayers());
        assertEquals(1000L, sent.get(0).get("intervalMs"));
    }

    @Test
    void failingSnapshot_sendsNothing() {
        MapReportingService service = new MapReportingService(true, noOutlines, () -> {
            throw new IllegalStateException("boom");
        }, 40, sender, Logger.getLogger("test"));

        assertNull(service.collectPlayers());
        assertTrue(sent.isEmpty());
    }
}
