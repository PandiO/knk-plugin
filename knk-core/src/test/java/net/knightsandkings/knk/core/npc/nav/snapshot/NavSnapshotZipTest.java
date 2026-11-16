package net.knightsandkings.knk.core.npc.nav.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The snapshot zip of link B1 (PandiO/knk-workspace#670): written and read back whole. */
class NavSnapshotZipTest {

    static NavSnapshot sample() {
        NavSnapshot.Manifest manifest = new NavSnapshot.Manifest(NavSnapshot.FORMAT, "quarry-to-keep", "world",
            new int[] {1400, 40, -560}, new int[] {1440, 90, -480}, "2026-11-16T21:00:00Z", "0.1.0+abc1234",
            Map.of("route points", 3, "domains", 2), List.of("world/region/r.2.-2.mca"), List.of("saved"));
        NavSnapshot.RouteLine route = new NavSnapshot.RouteLine("quarry-to-keep", new double[] {1430.5, 48, -520.5},
            new double[] {1414.5, 75, -544.5}, "FOUND", null,
            List.of(new double[] {1430.5, 48, -520.5}, new double[] {1413.5, 48, -520.5}, new double[] {1413.5, 50, -489.5}),
            List.of(List.of(new double[] {1430.5, 49, -520.5}, new double[] {1425.5, 49, -520.5})), "from the start",
            List.of(new NavSnapshot.GateCrossing(2000131, new double[] {1413.5, 48, -510.5})),
            List.of(new NavSnapshot.Plaza(new double[] {1413, 48, -521}, 0)));
        NavSnapshot.Roads roads = new NavSnapshot.Roads(
            List.of(new NavSnapshot.Node(1, 1430, 48, -521, "ENDPOINT", null, 0)),
            List.of(new NavSnapshot.Edge(5, 1, 2, List.of(new int[] {1430, 48, -521}, new int[] {1413, 48, -521}), 17,
                3, "main", List.of(), List.of(2000131), List.of("district_1000004"), List.of(1000004))));
        NavSnapshot.Domain district = new NavSnapshot.Domain("district_1000004", 1000004, "Residential District",
            "district", "polygon", List.of(new int[] {1400, -560}, new int[] {1440, -560}, new int[] {1440, -480}),
            0, 255, "town_1", 20, "deny", null, false, true, List.of());
        NavSnapshot.Domain gate = new NavSnapshot.Domain("gate_2000131", 2000131, "South Gate", "gate", "cuboid",
            List.of(new int[] {1410, 48, -512}, new int[] {1416, 56, -509}), 48, 56, "district_1000004", 30, null, null,
            true, true, List.of(new NavSnapshot.GateDoor(7, "south door", "CLOSED", false)));
        return new NavSnapshot(manifest, route, roads, List.of(district, gate),
            List.of(new NavSnapshot.Setting("stretch-blocks", "32.0", "default")));
    }

    @Test
    void writesAndReadsEveryPartAndTheRegionFiles(@TempDir Path dir) throws IOException {
        Path mca = dir.resolve("r.2.-2.mca");
        Files.write(mca, new byte[] {1, 2, 3, 4});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NavSnapshotZip.write(sample(), Map.of("world/region/r.2.-2.mca", mca), out);

        NavSnapshotZip.Loaded loaded = NavSnapshotZip.read(new ByteArrayInputStream(out.toByteArray()));
        NavSnapshot s = loaded.snapshot();
        assertEquals("quarry-to-keep", s.manifest().route());
        assertArrayEquals(new int[] {1400, 40, -560}, s.manifest().boxMin());
        assertEquals(3, s.route().line().size());
        assertArrayEquals(new double[] {1413.5, 50, -489.5}, s.route().line().get(2));
        assertEquals(1, s.route().stretches().size());
        assertEquals(2000131, s.route().gates().get(0).doorId());
        assertEquals("main", s.roads().edges().get(0).roadClass());
        assertEquals(2, s.domains().size());
        assertEquals("deny", s.domains().get(0).entryFlag());
        assertNull(s.domains().get(0).exitFlag());
        assertEquals(false, s.domains().get(0).allowEntry());
        assertTrue(s.domains().get(1).isGate());
        assertEquals("CLOSED", s.domains().get(1).gateDoors().get(0).state());
        assertEquals("default", s.settings().get(0).layer());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, loaded.regionFile("r.2.-2.mca"));
        assertNull(loaded.regionFile("r.0.0.mca"));
    }

    @Test
    void theReadmeSaysWhatEachFileHolds() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NavSnapshotZip.write(sample(), Map.of(), out);
        String readme = NavSnapshotZip.readme(sample());
        assertTrue(readme.contains("Route quarry-to-keep in world world"), readme);
        assertTrue(readme.contains(NavSnapshotZip.DOMAINS), readme);
    }

    @Test
    void refusesRegionFilesOutsideTheWorldFolder(@TempDir Path dir) throws IOException {
        Path mca = dir.resolve("r.0.0.mca");
        Files.write(mca, new byte[] {0});
        assertThrows(IllegalArgumentException.class,
            () -> NavSnapshotZip.write(sample(), Map.of("r.0.0.mca", mca), new ByteArrayOutputStream()));
    }

    @Test
    void refusesAnotherFormatAndUnsafePaths() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(NavSnapshotZip.MANIFEST));
            zip.write("{\"format\": 99}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        IOException format = assertThrows(IOException.class,
            () -> NavSnapshotZip.read(new ByteArrayInputStream(out.toByteArray())));
        assertTrue(format.getMessage().contains("format 99"), format.getMessage());

        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bad)) {
            zip.putNextEntry(new ZipEntry("world/../../evil"));
            zip.write(1);
            zip.closeEntry();
        }
        assertThrows(IOException.class, () -> NavSnapshotZip.read(new ByteArrayInputStream(bad.toByteArray())));
    }
}
