package net.knightsandkings.knk.core.statistics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One file per batch, atomic, versioned; round trip of every entry kind; oldest first. */
class StatisticsSpoolTest {

    private static final Instant T0 = Instant.parse("2026-10-03T10:00:00Z");

    @TempDir
    Path dir;

    static StatisticsBatch sample(UUID id, Instant sentAt) {
        UUID session = UUID.fromString("00000000-0000-0000-0000-000000000005");
        return new StatisticsBatch(id, sentAt,
                List.of(StatisticsBatch.SessionEntry.start(session, 7, T0),
                        StatisticsBatch.SessionEntry.end(session, 7, T0.plusSeconds(90), StatisticsBatch.EndReason.ServerStop)),
                List.of(new StatisticsBatch.DurationEntry(session, 7, "afk_time", T0, T0.plusSeconds(90))),
                List.of(new StatisticsBatch.ValueEntry(7, "distance.foot", "", 12.25, T0.plusSeconds(30))),
                List.of(new StatisticsBatch.ValueEntry(7, "highest_fall", "", 23.5, T0.plusSeconds(31))),
                List.of(new StatisticsBatch.PvpKillEntry(7, 8, "open_world", T0.plusSeconds(32))));
    }

    @Test
    void aBatchRoundTripsThroughItsFile() throws Exception {
        StatisticsSpool spool = new StatisticsSpool(dir.resolve("statistics-spool"), Logger.getAnonymousLogger());
        UUID id = UUID.randomUUID();
        StatisticsBatch batch = sample(id, T0);

        assertTrue(spool.write(batch));

        assertTrue(Files.isRegularFile(dir.resolve("statistics-spool").resolve(id + ".json")));
        assertFalse(Files.exists(dir.resolve("statistics-spool").resolve(id + ".json.tmp")));
        String json = Files.readString(dir.resolve("statistics-spool").resolve(id + ".json"));
        assertTrue(json.contains("\"version\":1"), json);
        assertEquals(List.of(batch), spool.list());
        assertTrue(spool.contains(id));
        assertEquals(1, spool.count());
    }

    @Test
    void listIsOldestFirstAndDeleteRemovesTheFile() {
        StatisticsSpool spool = new StatisticsSpool(dir, Logger.getAnonymousLogger());
        UUID newer = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        spool.write(sample(newer, T0.plusSeconds(60)));
        spool.write(sample(older, T0));

        assertEquals(List.of(older, newer), spool.list().stream().map(StatisticsBatch::batchId).toList());

        spool.delete(older);
        assertEquals(List.of(newer), spool.list().stream().map(StatisticsBatch::batchId).toList());
        spool.delete(newer);
        assertTrue(spool.isEmpty());
    }

    @Test
    void unreadableFilesAreSkippedAndLeftInPlace() throws Exception {
        StatisticsSpool spool = new StatisticsSpool(dir, Logger.getAnonymousLogger());
        Files.writeString(dir.resolve("broken.json"), "{not json");
        spool.write(sample(UUID.randomUUID(), T0));

        assertEquals(1, spool.list().size());
        assertEquals(2, spool.count());
        assertTrue(Files.exists(dir.resolve("broken.json")));
    }

    @Test
    void aMissingDirectoryIsAnEmptySpool() {
        StatisticsSpool spool = new StatisticsSpool(dir.resolve("nope"), Logger.getAnonymousLogger());
        assertTrue(spool.isEmpty());
        assertEquals(List.of(), spool.list());
    }

    // ---- account erasure (PandiO/knk-workspace#184) ----

    @Test
    void removeUser_DropsTheirEntriesAndKillsEitherWay_DeletesEmptiedBatches() {
        StatisticsSpool spool = new StatisticsSpool(dir, Logger.getAnonymousLogger());
        UUID onlyUser7 = UUID.randomUUID();
        spool.write(sample(onlyUser7, T0));
        UUID mixed = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        spool.write(new StatisticsBatch(mixed, T0.plusSeconds(5),
                List.of(StatisticsBatch.SessionEntry.start(session, 9, T0)),
                List.of(),
                List.of(new StatisticsBatch.ValueEntry(7, "distance.foot", "", 1, T0), new StatisticsBatch.ValueEntry(9, "distance.foot", "", 2, T0)),
                List.of(),
                List.of(new StatisticsBatch.PvpKillEntry(9, 7, "open_world", T0), new StatisticsBatch.PvpKillEntry(9, 8, "open_world", T0))));

        assertEquals(6 + 2, spool.removeUser(7));

        assertFalse(spool.contains(onlyUser7));
        StatisticsBatch kept = spool.list().get(0);
        assertEquals(mixed, kept.batchId());
        assertEquals(List.of(9), kept.counters().stream().map(StatisticsBatch.ValueEntry::userId).toList());
        assertEquals(List.of(8), kept.pvpKills().stream().map(StatisticsBatch.PvpKillEntry::victimUserId).toList());
        assertEquals(1, kept.sessions().size());
        assertEquals(0, spool.removeUser(7));
    }
}
