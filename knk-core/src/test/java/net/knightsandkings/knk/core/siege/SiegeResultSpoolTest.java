package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.Completion;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ObjectiveResult;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchRecords.ParticipantResult;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;
import net.knightsandkings.knk.core.siege.SiegeResultSpool.PendingAbort;
import net.knightsandkings.knk.core.siege.SiegeResultSpool.PendingComplete;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 6: the pending-results spool (siege-vault/pending-results/&lt;matchId&gt;.json). */
class SiegeResultSpoolTest {

    @TempDir
    Path dir;

    static Completion completion() {
        return new Completion(SiegeEndReason.INSTANT_VICTORY, 2,
                List.of(new ParticipantResult(7, 202, 3, 1, 2, 1).withContribution(312.5, 540, 600),
                        new ParticipantResult(8, 201, 0, 2, 0, 0),
                        // KNG-34: a departed member round-trips with its leftAt
                        new ParticipantResult(9, 201, 1, 1, 1, 0, Instant.parse("2026-09-26T21:01:00Z"))),
                List.of(new ObjectiveResult(501, 202, 7, Instant.parse("2026-09-26T21:05:00Z")),
                        new ObjectiveResult(502, 201, null, null)));
    }

    private SiegeResultSpool spool() {
        return new SiegeResultSpool(dir.resolve("pending-results"), Logger.getLogger("test"));
    }

    @Test
    void completeAndAbort_roundTripThroughTheFiles_oldestMatchFirst() {
        SiegeResultSpool spool = spool();
        assertTrue(spool.isEmpty());

        assertTrue(spool.save(new PendingComplete(12, completion())));
        assertTrue(spool.save(new PendingAbort(5, SiegeEndReason.ADMIN_STOPPED)));

        // A fresh instance reads what the other one wrote (the next enable).
        var listed = new SiegeResultSpool(dir.resolve("pending-results"), Logger.getLogger("test")).list();
        assertEquals(List.of(new PendingAbort(5, SiegeEndReason.ADMIN_STOPPED), new PendingComplete(12, completion())), listed);
        assertTrue(Files.isRegularFile(dir.resolve("pending-results").resolve("12.json")));
        assertFalse(Files.exists(dir.resolve("pending-results").resolve("12.json.tmp")));
    }

    @Test
    void aLaterSaveForTheSameMatchReplacesTheEarlierOne_andDeleteRemovesIt() {
        SiegeResultSpool spool = spool();
        spool.save(new PendingAbort(3, SiegeEndReason.SERVER_RESTART));
        spool.save(new PendingComplete(3, completion()));

        assertEquals(List.of(new PendingComplete(3, completion())), spool.list());
        assertTrue(spool.contains(3));

        spool.delete(3);
        spool.delete(3); // already gone: no error
        assertFalse(spool.contains(3));
        assertTrue(spool.isEmpty());
    }

    @Test
    void unreadableFilesAreSkippedAndLeftInPlace() throws Exception {
        SiegeResultSpool spool = spool();
        spool.save(new PendingAbort(1, SiegeEndReason.SERVER_RESTART));
        Path broken = dir.resolve("pending-results").resolve("2.json");
        Files.writeString(broken, "{ not json");
        Files.writeString(dir.resolve("pending-results").resolve("notes.txt"), "ignored");

        assertEquals(List.of(new PendingAbort(1, SiegeEndReason.SERVER_RESTART)), spool.list());
        assertTrue(Files.exists(broken));
    }

    @Test
    void missingDirectoryIsAnEmptySpool() {
        assertEquals(List.of(), new SiegeResultSpool(dir.resolve("nope"), Logger.getLogger("test")).list());
    }

    @Test
    void anOlderFileWithoutGateDamageOrActivity_readsAsZero() throws Exception {
        Path folder = dir.resolve("pending-results");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("3.json"), """
                {"version":1,"type":"complete","matchId":3,"endReason":"TIME_EXPIRED","winningAllianceGroup":null,
                 "participants":[{"userId":7,"siegeTeamId":202,"kills":1,"deaths":0,"highestKillStreak":1,"captures":0,"leftAt":null}],
                 "objectives":[],"spooledAt":"2026-10-27T12:00:00Z"}""");

        PendingComplete read = (PendingComplete) spool().list().get(0);

        ParticipantResult p = read.completion().participants().get(0);
        assertEquals(0.0, p.gateDamage());
        assertEquals(0, p.activeSeconds());
        assertEquals(0, p.matchSeconds());
    }
}
