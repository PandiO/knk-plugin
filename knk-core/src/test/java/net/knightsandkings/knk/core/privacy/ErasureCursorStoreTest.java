package net.knightsandkings.knk.core.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErasureCursorStoreTest {

    @TempDir
    Path dir;

    private ErasureCursorStore store() {
        return new ErasureCursorStore(dir.resolve("erasure-feed.json"), Logger.getAnonymousLogger());
    }

    @Test
    void startsAtZero_AdvancesOnlyForward_AndSurvivesARestart() throws Exception {
        ErasureCursorStore store = store();
        assertEquals(0, store.lastId());
        assertFalse(Files.exists(dir.resolve("erasure-feed.json")));

        store.advanceTo(12);
        store.advanceTo(7);

        assertEquals(12, store.lastId());
        assertEquals(12, store().lastId());
        String json = Files.readString(dir.resolve("erasure-feed.json"));
        assertTrue(json.contains("\"version\":1") && json.contains("\"lastId\":12"), json);
        assertFalse(Files.exists(dir.resolve("erasure-feed.json.tmp")));
    }

    @Test
    void reset_GoesBackToZero() {
        ErasureCursorStore store = store();
        store.advanceTo(40);

        store.reset();

        assertEquals(0, store.lastId());
        assertEquals(0, store().lastId());
    }

    @Test
    void anUnreadableFile_StartsFromTheBeginning() throws Exception {
        Files.writeString(dir.resolve("erasure-feed.json"), "{not json");

        assertEquals(0, store().lastId());
    }
}
