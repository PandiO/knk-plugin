package net.knightsandkings.knk.paper.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.realtime.ConfigEvents;
import net.knightsandkings.knk.api.realtime.RealtimeEnvelope;
import net.knightsandkings.knk.core.realtime.Coalescer;
import net.knightsandkings.knk.core.realtime.EntityChangeRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PandiO/knk-workspace#182 (S3, DESIGN §6.2–§6.3): each config event reaches its existing refresh path once per burst,
 * {@code EntityChanged} reaches the caches of its type, and a gate held by a running siege match is reloaded only after
 * the match released it (decision Q12).
 */
class ConfigRealtimeSyncTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final Coalescer coalescer = new Coalescer((delay, task) -> scheduled.add(task), Duration.ofMillis(500));
    private final List<String> calls = new ArrayList<>();
    private final Set<Integer> lockedByMatch = new HashSet<>();
    private final Logger logger = Logger.getAnonymousLogger();
    private RealtimeDispatcher dispatcher;
    private GateRealtimeSync gates;

    @BeforeEach
    void setUp() {
        dispatcher = new RealtimeDispatcher(logger);
        gates = new GateRealtimeSync(coalescer, lockedByMatch::contains, id -> {
            calls.add("reload gate " + id);
            return CompletableFuture.completedFuture(null);
        }, id -> calls.add("remove gate " + id), logger);
        var targets = new ConfigRealtimeSync.Targets(
            () -> calls.add("domain access"), () -> calls.add("game settings"), () -> calls.add("siege config"),
            () -> calls.add("lootbox"), () -> calls.add("teleport"), () -> calls.add("navigation"),
            world -> calls.add("roads " + world), () -> calls.add("telemetry"));
        var router = new EntityChangeRouter()
            .on("Town", (id, key, op) -> calls.add("town " + id + " " + key + " " + op))
            .on("Kit", (id, key, op) -> calls.add("kit " + id));
        new ConfigRealtimeSync(coalescer, targets, router, gates, logger).register(dispatcher);
    }

    private void send(String type, String payload) {
        assertTrue(dispatcher.dispatch(RealtimeEnvelope.parse("{\"v\":1,\"seq\":2,\"type\":\"" + type + "\",\"payload\":"
            + payload + "}", mapper)), type);
    }

    private void runScheduled() {
        List<Runnable> now = List.copyOf(scheduled);
        scheduled.clear();
        now.forEach(Runnable::run);
    }

    @Test
    void everyS3TypeIsRegistered() {
        assertTrue(dispatcher.types().containsAll(ConfigEvents.TYPES));
    }

    @Test
    void sectionEvents_refreshOncePerBurst() {
        send(ConfigEvents.DOMAIN_ACCESS_CHANGED, "{\"domainId\":8}");
        send(ConfigEvents.DOMAIN_ACCESS_CHANGED, "{\"domainId\":9}");
        send(ConfigEvents.GAME_SETTINGS_CHANGED, "{\"scope\":\"hub\"}");
        send(ConfigEvents.SIEGE_CONFIG_CHANGED, "{\"scope\":\"scenario\"}");
        send(ConfigEvents.SIEGE_CONFIG_CHANGED, "{\"scope\":\"lobby\"}");
        send(ConfigEvents.LOOTBOX_CONFIG_CHANGED, "{\"scope\":\"type\"}");
        send(ConfigEvents.TELEPORT_DESTINATIONS_CHANGED, "{\"scope\":\"domain\"}");
        send(ConfigEvents.NAVIGATION_SETTINGS_CHANGED, "{\"scope\":\"compass\"}");
        send(ConfigEvents.TELEMETRY_CONFIG_CHANGED, "{\"scope\":\"testRuns\"}");
        assertEquals(List.of(), calls); // nothing before the burst window ends

        runScheduled();

        assertEquals(List.of("domain access", "game settings", "siege config", "lootbox", "teleport", "navigation", "telemetry"), calls);
    }

    @Test
    void roads_perWorld() {
        send(ConfigEvents.ROAD_NETWORK_CHANGED, "{\"world\":\"world\"}");
        send(ConfigEvents.ROAD_NETWORK_CHANGED, "{\"world\":\"world\"}");
        send(ConfigEvents.ROAD_NETWORK_CHANGED, "{\"world\":null}");
        runScheduled();
        assertEquals(List.of("roads world", "roads null"), calls);
    }

    @Test
    void entityChanged_goesToTheCachesOfItsType_atOnce() {
        send(ConfigEvents.ENTITY_CHANGED, "{\"type\":\"Town\",\"id\":4,\"op\":\"Updated\",\"key\":\"town_4\"}");
        send(ConfigEvents.ENTITY_CHANGED, "{\"type\":\"Kit\",\"id\":null,\"op\":\"Updated\"}");
        send(ConfigEvents.ENTITY_CHANGED, "{\"type\":\"Street\",\"id\":1,\"op\":\"Updated\"}"); // not cached: ignored
        assertEquals(List.of("town 4 town_4 Updated", "kit null"), calls);
    }

    @Test
    void gateChanged_reloadsThatStructureOncePerBurst_andRemovesADeletedOne() {
        send(ConfigEvents.GATE_CHANGED, "{\"gateStructureId\":3,\"op\":\"Updated\"}");
        send(ConfigEvents.GATE_CHANGED, "{\"gateStructureId\":3,\"op\":\"Updated\"}");
        send(ConfigEvents.GATE_CHANGED, "{\"gateStructureId\":5,\"op\":\"Deleted\"}");
        runScheduled();
        assertEquals(List.of("reload gate 3", "remove gate 5"), calls);
    }

    @Test
    void gateChanged_duringAMatch_waitsForTheRelease() {
        lockedByMatch.add(3);
        send(ConfigEvents.GATE_CHANGED, "{\"gateStructureId\":3,\"op\":\"Updated\"}");
        send(ConfigEvents.GATE_CHANGED, "{\"gateStructureId\":4,\"op\":\"Updated\"}");
        runScheduled();
        assertEquals(List.of("reload gate 4"), calls); // gate 4 isn't in the match
        assertEquals(List.of(3), gates.deferred());

        gates.matchReleased(); // another lobby's round ended: gate 3 is still locked
        assertEquals(List.of("reload gate 4"), calls);

        lockedByMatch.remove(3);
        gates.matchReleased();
        assertEquals(List.of("reload gate 4", "reload gate 3"), calls);
        assertEquals(List.of(), gates.deferred());
    }

    @Test
    void gateChanged_deleteDuringAMatchStaysADelete() {
        lockedByMatch.add(3);
        gates.apply(3, true);
        gates.apply(3, false);
        lockedByMatch.clear();
        gates.matchReleased();
        assertEquals(List.of("remove gate 3"), calls);
    }

    @Test
    void featureOff_ignoresItsEvent() {
        var off = new RealtimeDispatcher(logger);
        new ConfigRealtimeSync(coalescer, new ConfigRealtimeSync.Targets(null, null, null, null, null, null, null, null),
            new EntityChangeRouter(), null, logger).register(off);
        assertTrue(off.dispatch(RealtimeEnvelope.parse("{\"v\":1,\"seq\":2,\"type\":\"GameSettingsChanged\",\"payload\":{}}", mapper)));
        assertTrue(off.dispatch(RealtimeEnvelope.parse("{\"v\":1,\"seq\":3,\"type\":\"GateChanged\",\"payload\":{\"gateStructureId\":1}}", mapper)));
        assertTrue(off.dispatch(RealtimeEnvelope.parse("{\"v\":1,\"seq\":4,\"type\":\"RoadNetworkChanged\",\"payload\":{}}", mapper)));
        assertEquals(List.of(), scheduled);
    }
}
