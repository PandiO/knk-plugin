package net.knightsandkings.knk.paper.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import net.knightsandkings.knk.core.domain.users.ActiveMode;
import net.knightsandkings.knk.core.map.MapReportSender;
import net.knightsandkings.knk.core.map.MapReportingService;
import net.knightsandkings.knk.core.map.OnlinePlayerPosition;

/**
 * Overview map L4 (PandiO/knk-workspace#738, OVERVIEW_MAP_DESIGN § 7.2, § 9.1, A11, A13, A14): the Paper snapshot of the
 * online players and the scheduled report (snapshot on the main thread, send on an async task, every interval).
 */
class PaperOnlinePlayerSnapshotTest {

    private ServerMock server;
    private Plugin plugin;
    private World world;
    private PlayerMock alice;
    private PlayerMock bob;
    private final Set<UUID> afk = new java.util.HashSet<>();
    private final Map<UUID, ActiveMode> modes = new java.util.HashMap<>();
    private final Map<UUID, PaperOnlinePlayerSnapshot.MinigameMatch> matches = new java.util.HashMap<>();
    private final List<Map<String, Object>> sent = Collections.synchronizedList(new ArrayList<>());
    private final List<String> sendThreads = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("w");
        plugin = MockBukkit.createMockPlugin("KnightsAndKings");
        alice = server.addPlayer("Alice");
        bob = server.addPlayer("Bob");
        alice.teleport(new Location(world, 10.5, 64, -20.25, 90f, 0f));
        bob.teleport(new Location(world, -3, 70, 5, -45f, 0f));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PaperOnlinePlayerSnapshot snapshot() {
        return new PaperOnlinePlayerSnapshot(() -> server.getOnlinePlayers(), afk::contains,
                player -> modes.getOrDefault(player.getUniqueId(), ActiveMode.NONE), matches::get);
    }

    private OnlinePlayerPosition of(List<OnlinePlayerPosition> list, PlayerMock player) {
        return list.stream().filter(p -> p.uuid().equals(player.getUniqueId())).findFirst().orElseThrow();
    }

    @Test
    void snapshot_readsWorldPositionYawAndDefaults() {
        List<OnlinePlayerPosition> list = snapshot().snapshot();

        assertEquals(2, list.size());
        OnlinePlayerPosition a = of(list, alice);
        assertEquals("w", a.world());
        assertEquals(10.5, a.x());
        assertEquals(64.0, a.y());
        assertEquals(-20.25, a.z());
        assertEquals(90f, a.yaw());
        assertFalse(a.afk());
        assertEquals(ActiveMode.NONE, a.mode());
        assertNull(a.minigame());
        assertNull(a.matchId());
    }

    @Test
    void snapshot_readsAfkModeAndRunningMatch() {
        afk.add(alice.getUniqueId());
        modes.put(bob.getUniqueId(), ActiveMode.STAFF);
        matches.put(alice.getUniqueId(), new PaperOnlinePlayerSnapshot.MinigameMatch("siege", 42L));

        List<OnlinePlayerPosition> list = snapshot().snapshot();

        assertTrue(of(list, alice).afk());
        assertEquals("siege", of(list, alice).minigame());
        assertEquals(42L, of(list, alice).matchId());
        assertEquals(ActiveMode.STAFF, of(list, bob).mode());
        assertFalse(of(list, bob).afk());
    }

    @Test
    void snapshot_withoutOptionalServices_isPlain() {
        List<OnlinePlayerPosition> list = new PaperOnlinePlayerSnapshot(() -> server.getOnlinePlayers(), null, null, null).snapshot();

        assertEquals(2, list.size());
        assertTrue(list.stream().allMatch(p -> !p.afk() && p.mode() == ActiveMode.NONE && p.minigame() == null));
    }

    @Test
    void snapshot_ofAnEmptyServer_isEmpty() {
        assertTrue(new PaperOnlinePlayerSnapshot(List::of, null, null, null).snapshot().isEmpty());
    }

    private MapReportingService service(boolean enabled) {
        MapReportSender sender = (method, argument) -> {
            sendThreads.add(Thread.currentThread().getName());
            sent.add(argument);
            return true;
        };
        return new MapReportingService(enabled, (w, id) -> Optional.empty(), snapshot(), 40, sender, Logger.getLogger("test"));
    }

    /**
     * Ticks one at a time until {@code expected} reports were sent; returns the ticks it took, or -1. The send runs on
     * MockBukkit's async executor, so each tick waits briefly for it (MockBukkit's {@code waitAsyncTasksFinished}
     * would cancel the repeating report task).
     */
    private int ticksUntilSent(int expected, int maxTicks) throws InterruptedException {
        for (int tick = 1; tick <= maxTicks; tick++) {
            server.getScheduler().performOneTick();
            long deadline = System.nanoTime() + 200_000_000L;
            while (sent.size() < expected && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            if (sent.size() >= expected) {
                return tick;
            }
        }
        return -1;
    }

    @Test
    @SuppressWarnings("unchecked")
    void startPlayerReports_sendsEveryIntervalOffTheMainThread() throws Exception {
        assertTrue(MapReporting.startPlayerReports(plugin, service(true)));

        server.getScheduler().performTicks(39);
        Thread.sleep(50);
        assertTrue(sent.isEmpty());

        int first = ticksUntilSent(1, 3);
        assertTrue(first > 0, "first report within a few ticks of the interval");
        assertEquals(1, sent.size());
        assertEquals(2, ((List<?>) sent.get(0).get("players")).size());
        assertFalse(sendThreads.contains(Thread.currentThread().getName()));

        alice.teleport(new Location(world, 30.5, 64, -20.25));
        int second = ticksUntilSent(2, 45);
        assertTrue(second >= 38 && second <= 41, "second report one interval later, took " + second);
        Map<String, Object> moved = (Map<String, Object>) ((List<?>) sent.get(1).get("players")).stream()
                .filter(p -> ((Map<?, ?>) p).get("uuid").equals(alice.getUniqueId().toString())).findFirst().orElseThrow();
        assertEquals(30.5, moved.get("x"));
    }

    @Test
    void startPlayerReports_offDoesNotSchedule() {
        assertFalse(MapReporting.startPlayerReports(plugin, service(false)));
        assertFalse(MapReporting.startPlayerReports(plugin, null));

        server.getScheduler().performTicks(200);
        assertTrue(sent.isEmpty());
        assertEquals(0, server.getScheduler().getPendingTasks().size());
    }
}
