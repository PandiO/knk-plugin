package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.knightsandkings.knk.core.npc.nav.NavPoint;
import net.knightsandkings.knk.paper.npc.nav.NpcRouteStore.Route;

class NpcRouteStoreTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final NavPoint FROM = new NavPoint("world_KNK-DEV", 1408.5, 49, -489.5);
    private static final NavPoint TO = new NavPoint("world_KNK-DEV", 1416.5, 62, -503.5);

    @TempDir
    Path folder;

    @Test
    void theCurrentRouteAndNamedRoutesSurviveARestart() {
        NpcRouteStore store = new NpcRouteStore(folder.toFile(), LOG);
        assertFalse(store.current().complete());
        store.saveCurrent(new Route(FROM, null));
        assertNull(store.current().to());
        store.saveCurrent(new Route(FROM, TO));
        store.saveNamed("Stair House!", new Route(FROM, TO));

        NpcRouteStore restarted = new NpcRouteStore(folder.toFile(), LOG);

        assertEquals(new Route(FROM, TO), restarted.current());
        assertEquals(List.of("stair-house-"), restarted.names());
        assertEquals(new Route(FROM, TO), restarted.named("stair house!").orElseThrow());
        assertTrue(restarted.delete("stair-house-"));
        assertFalse(restarted.delete("stair-house-"));
        assertTrue(new NpcRouteStore(folder.toFile(), LOG).names().isEmpty());
    }

    @Test
    void theSpikesRoutesAreTakenOverOnce() throws Exception {
        Files.writeString(folder.resolve(NpcRouteStore.SPIKE_FILE), """
            current:
              from: {world: world_KNK-DEV, x: 1.5, y: 64.0, z: 2.5}
              to: {world: world_KNK-DEV, x: 9.5, y: 64.0, z: 2.5}
              subject: 5f0c2f5e-0000-0000-0000-000000000000
            routes:
              south-gate:
                from: {world: world_KNK-DEV, x: 1408.5, y: 49.0, z: -489.5}
                to: {world: world_KNK-DEV, x: 1416.5, y: 62.0, z: -503.5}
                subject: 5f0c2f5e-0000-0000-0000-000000000000
            """);

        NpcRouteStore store = new NpcRouteStore(folder.toFile(), LOG);

        assertEquals(new Route(FROM, TO), store.named("south-gate").orElseThrow());
        assertEquals(1.5, store.current().from().x());
        assertTrue(new File(folder.toFile(), NpcRouteStore.FILE).exists(), "saved as the store's own file");

        store.delete("south-gate");
        assertTrue(new NpcRouteStore(folder.toFile(), LOG).names().isEmpty(), "its own file wins from then on");
    }

    @Test
    void describesAPoint() {
        assertEquals("world_KNK-DEV 1408 49 -490", NpcRouteStore.describe(FROM));
        assertEquals("unset", NpcRouteStore.describe(null));
    }
}
