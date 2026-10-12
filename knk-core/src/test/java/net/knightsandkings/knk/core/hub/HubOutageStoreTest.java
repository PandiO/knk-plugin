package net.knightsandkings.knk.core.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.knightsandkings.knk.core.hub.HubOutageStore.ReturnRecord;

/** Hub DESIGN §12: the outage timer and return records survive a restart without the API. */
class HubOutageStoreTest {

    @TempDir
    Path dir;

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant T0 = Instant.parse("2026-10-12T10:00:00Z");

    @Test
    void aRestartKeepsTheOutageStartAndTheReturnRecords() {
        Path file = dir.resolve("hub-outage.json");
        HubOutageStore store = new HubOutageStore(file);
        store.setOutageStartedAt(T0);
        assertTrue(store.saveIfAbsent(new ReturnRecord(ALICE, "world", 10.5, 64, -3.5, 90f, 0f, T0)));

        HubOutageStore reloaded = new HubOutageStore(file);
        assertEquals(T0, reloaded.outageStartedAt().orElseThrow());
        ReturnRecord record = reloaded.returnRecord(ALICE).orElseThrow();
        assertEquals("world", record.world());
        assertEquals(-3.5, record.z());
        assertEquals(T0, record.savedAt());
    }

    @Test
    void aLaterEvacuationKeepsTheOriginalSpot() {
        HubOutageStore store = new HubOutageStore(null);
        store.saveIfAbsent(new ReturnRecord(ALICE, "world", 1, 64, 1, 0f, 0f, T0));
        assertFalse(store.saveIfAbsent(new ReturnRecord(ALICE, "hub", 2, 65, 2, 0f, 0f, T0.plusSeconds(60))));
        assertEquals("world", store.returnRecord(ALICE).orElseThrow().world());
        assertTrue(store.remove(ALICE).isPresent());
        assertTrue(store.returnRecord(ALICE).isEmpty());
    }

    @Test
    void aCorruptFileIsMovedAside() throws IOException {
        Path file = dir.resolve("hub-outage.json");
        Files.writeString(file, "{not json");
        HubOutageStore store = new HubOutageStore(file);
        assertTrue(store.outageStartedAt().isEmpty());
        assertTrue(Files.exists(dir.resolve("hub-outage.json.corrupt")));
    }

    @Test
    void aNewOutageReplacesAnOfferThatWasNeverUsed() {
        HubOutageStore store = new HubOutageStore(null);
        store.save(new ReturnRecord(ALICE, "world", 1, 64, 1, 0f, 0f, T0), T0);
        Instant secondOutage = T0.plusSeconds(3600);
        assertFalse(store.save(new ReturnRecord(ALICE, "world", 2, 64, 2, 0f, 0f, T0.plusSeconds(60)), T0), "same outage");
        assertTrue(store.save(new ReturnRecord(ALICE, "world", 3, 64, 3, 0f, 0f, secondOutage.plusSeconds(5)), secondOutage));
        assertEquals(3, store.returnRecord(ALICE).orElseThrow().x());
    }
}
