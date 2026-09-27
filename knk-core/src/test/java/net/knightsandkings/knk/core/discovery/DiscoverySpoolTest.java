package net.knightsandkings.knk.core.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.knightsandkings.knk.core.domain.discovery.DiscoverySource;

class DiscoverySpoolTest {
    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant T0 = Instant.parse("2026-09-26T12:00:00Z");

    @TempDir
    Path dir;

    private DiscoverySpool spool() {
        return new DiscoverySpool(dir.resolve("discovery-spool"), Logger.getLogger("test"));
    }

    @Test
    void writesOneFilePerPlayerAtomicallyAndReadsItBack() throws Exception {
        DiscoverySpool spool = spool();
        assertTrue(spool.add(PLAYER, 7, List.of(new PendingDiscovery("town_rivia", DiscoverySource.JOIN_INSIDE, T0))));

        assertTrue(Files.isRegularFile(dir.resolve("discovery-spool").resolve(PLAYER + ".json")));
        try (var files = Files.list(dir.resolve("discovery-spool"))) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".tmp")), "the temp file was moved into place");
        }
        DiscoverySpool.Pending pending = spool().get(PLAYER).orElseThrow();
        assertEquals(7, pending.userId());
        assertEquals(List.of(new PendingDiscovery("town_rivia", DiscoverySource.JOIN_INSIDE, T0)), pending.entries());
    }

    @Test
    void addingMergesByRegionIdKeepingTheEarliestSighting() {
        DiscoverySpool spool = spool();
        spool.add(PLAYER, 7, List.of(new PendingDiscovery("town_rivia", DiscoverySource.REGION_ENTER, T0.plusSeconds(10))));
        spool.add(PLAYER, 7, List.of(
                new PendingDiscovery("TOWN_RIVIA", DiscoverySource.REGION_ENTER, T0),
                new PendingDiscovery("district_market", DiscoverySource.REGION_ENTER, T0)));

        List<PendingDiscovery> entries = spool.get(PLAYER).orElseThrow().entries();
        assertEquals(2, entries.size());
        assertEquals(T0, entries.get(0).discoveredAt());
        assertEquals(2, spool.entryCount());
    }

    @Test
    void removingTheLastEntriesDeletesTheFile() {
        DiscoverySpool spool = spool();
        spool.add(PLAYER, 7, List.of(
                new PendingDiscovery("town_rivia", DiscoverySource.REGION_ENTER, T0),
                new PendingDiscovery("district_market", DiscoverySource.REGION_ENTER, T0)));

        spool.remove(PLAYER, List.of("Town_Rivia"));
        assertEquals(1, spool.get(PLAYER).orElseThrow().entries().size());

        spool.remove(PLAYER, List.of("district_market"));
        assertTrue(spool.get(PLAYER).isEmpty());
        assertTrue(spool.isEmpty());
        assertFalse(Files.exists(dir.resolve("discovery-spool").resolve(PLAYER + ".json")));
    }

    @Test
    void unreadableFilesAreSkippedByList() throws Exception {
        DiscoverySpool spool = spool();
        spool.add(PLAYER, 7, List.of(new PendingDiscovery("town_rivia", DiscoverySource.REGION_ENTER, T0)));
        Files.writeString(dir.resolve("discovery-spool").resolve(UUID.randomUUID() + ".json"), "{not json");

        assertEquals(1, spool.list().size());
    }

    @Test
    void anEntryWithAnUnknownUserIdRoundTrips_AndALaterKnownIdIsKept() throws Exception {
        DiscoverySpool spool = spool();
        assertTrue(spool.add(PLAYER, DiscoveryTracker.UNRESOLVED_USER,
                List.of(new PendingDiscovery("town_rivia", DiscoverySource.JOIN_INSIDE, T0))));

        String json = Files.readString(dir.resolve("discovery-spool").resolve(PLAYER + ".json"));
        assertTrue(json.contains("\"userId\" : null"), json);
        DiscoverySpool.Pending pending = spool().get(PLAYER).orElseThrow();
        assertEquals(DiscoveryTracker.UNRESOLVED_USER, pending.userId());
        assertFalse(pending.userResolved());
        assertEquals(List.of(new PendingDiscovery("town_rivia", DiscoverySource.JOIN_INSIDE, T0)), pending.entries());
        assertEquals(1, spool.list().size());

        // A later add with the id fills it in; another unknown add doesn't erase it again.
        spool.add(PLAYER, 7, List.of(new PendingDiscovery("district_market", DiscoverySource.REGION_ENTER, T0)));
        spool.add(PLAYER, DiscoveryTracker.UNRESOLVED_USER, List.of(new PendingDiscovery("smithy", DiscoverySource.REGION_ENTER, T0)));
        pending = spool.get(PLAYER).orElseThrow();
        assertEquals(7, pending.userId());
        assertEquals(3, pending.entries().size());
    }

    @Test
    void versionOneFilesStillRead() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("discovery-spool"));
        Files.writeString(folder.resolve(PLAYER + ".json"), """
                {
                  "version" : 1,
                  "playerId" : "11111111-2222-3333-4444-555555555555",
                  "userId" : 7,
                  "entries" : [ {
                    "regionId" : "town_rivia",
                    "source" : "JoinInside",
                    "discoveredAt" : "2026-09-26T12:00:00Z"
                  } ]
                }
                """);

        DiscoverySpool.Pending pending = spool().get(PLAYER).orElseThrow();

        assertEquals(7, pending.userId());
        assertTrue(pending.userResolved());
        assertEquals(List.of(new PendingDiscovery("town_rivia", DiscoverySource.JOIN_INSIDE, T0)), pending.entries());
        assertEquals(1, spool().list().size());
    }
}
