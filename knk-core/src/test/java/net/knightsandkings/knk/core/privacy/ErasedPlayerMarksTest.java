package net.knightsandkings.knk.core.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PandiO/knk-workspace#486: the erased players whose next join is a first join. */
class ErasedPlayerMarksTest {

    private static final UUID T2 = UUID.fromString("6a1f0c2e-3b4d-4e5f-8a9b-0c1d2e3f4a5b");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @TempDir
    Path dir;

    private Instant now = Instant.parse("2026-11-14T10:00:00Z");

    private Clock clock() {
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
    }

    private ErasedPlayerMarks marks() {
        return new ErasedPlayerMarks(dir.resolve("erasure-first-joins.json"), clock(), Logger.getAnonymousLogger());
    }

    @Test
    void aMark_IsTakenOnce() {
        ErasedPlayerMarks marks = marks();
        assertEquals(1, marks.mark(T2));

        assertTrue(marks.isMarked(T2));
        assertFalse(marks.isMarked(OTHER));
        assertTrue(marks.take(T2));
        assertFalse(marks.take(T2), "only the first join after the erasure");
        assertFalse(marks.take(OTHER));
    }

    @Test
    void aMark_SurvivesARestart_AndItsUseToo() {
        marks().mark(T2);

        ErasedPlayerMarks afterRestart = marks();
        assertTrue(afterRestart.take(T2));
        assertFalse(marks().take(T2), "taken before the next restart");
    }

    @Test
    void theFile_HoldsNoUuid() throws Exception {
        marks().mark(T2);

        String json = Files.readString(dir.resolve("erasure-first-joins.json"));
        assertFalse(json.contains(T2.toString()));
        assertFalse(json.toLowerCase().contains(T2.toString().replace("-", "")));
        assertTrue(json.contains(ErasedPlayerMarks.key(T2)));
    }

    @Test
    void anOldMark_IsDropped() {
        ErasedPlayerMarks marks = marks();
        marks.mark(T2);
        now = now.plus(ErasedPlayerMarks.MAX_AGE).plus(Duration.ofMinutes(1));

        assertFalse(marks.isMarked(T2));
        assertFalse(marks.take(T2));
        marks.mark(OTHER);
        assertEquals(1, marks.size(), "pruned on the next write");
    }

    @Test
    void anUnreadableFile_StartsEmpty() throws Exception {
        Files.writeString(dir.resolve("erasure-first-joins.json"), "{not json");

        ErasedPlayerMarks marks = marks();

        assertFalse(marks.isMarked(T2));
        marks.mark(T2);
        assertTrue(marks().isMarked(T2), "and is written fresh");
    }
}
