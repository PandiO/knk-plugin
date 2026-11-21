package net.knightsandkings.knk.core.production.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * PandiO/knk-workspace#134 (the L21 contract): batched harvest reports with one stable key per entry. Breaks of the same
 * player, rule, area and fortune level join one entry until it is sent; an entry without an answer is sent again with the
 * same key after a pause; entries survive a restart; worker's shares of offline players wait for them.
 */
class HarvestOutboxTest {

    private static final UUID ANNA = UUID.randomUUID();
    private static final UUID BEN = UUID.randomUUID();

    @Test
    void breaksOfTheSameKindJoinOneEntryUntilItIsSent() {
        HarvestOutbox outbox = new HarvestOutbox("alpha 1");
        String a = outbox.add(ANNA, 3, 7, 5, 0, 1000);
        assertEquals(a, outbox.add(ANNA, 3, 7, 5, 0, 1500));
        assertTrue(a.startsWith("harvest-alpha_1-"));
        assertNotEquals(a, outbox.add(ANNA, 3, 7, 5, 2, 1600)); // another fortune level
        assertNotEquals(a, outbox.add(BEN, 4, 7, 5, 0, 1600));  // another player
        assertEquals(a, outbox.add(ANNA, 3, 7, 5, 0, 1700, 4)); // a felled tree's four logs
        assertEquals(3, outbox.size());
        HarvestOutbox.Entry entry = outbox.get(a);
        assertEquals(6, entry.breaks());
        assertEquals(1000, entry.harvestedAt());

        List<HarvestOutbox.Entry> batch = outbox.take(10, 2000);
        assertEquals(3, batch.size());
        assertNotEquals(a, outbox.add(ANNA, 3, 7, 5, 0, 2100)); // sent entries are closed
        assertEquals(1, outbox.take(10, 2000).size());           // only the new one is ready
    }

    @Test
    void anEntryWithoutAnswerIsSentAgainWithTheSameKeyAfterAPause() {
        HarvestOutbox outbox = new HarvestOutbox("s");
        String key = outbox.add(ANNA, 3, 7, 5, 0, 1000);
        outbox.take(10, 1000);
        outbox.failed(List.of(key), 1000);
        assertEquals(0, outbox.take(10, 5999).size());
        List<HarvestOutbox.Entry> again = outbox.take(10, 6000);
        assertEquals(key, again.get(0).key());
        outbox.failed(List.of(key), 6000);
        assertEquals(0, outbox.take(10, 15_999).size()); // 10 s the second time
        assertEquals(1, outbox.take(10, 16_000).size());
        outbox.answered(key);
        assertEquals(0, outbox.size());
    }

    @Test
    void theBatchSizeIsCapped() {
        HarvestOutbox outbox = new HarvestOutbox("s");
        for (int i = 0; i < 5; i++) {
            outbox.add(ANNA, 3, 7, i, 0, 1000);
        }
        assertEquals(2, outbox.take(2, 1000).size());
        assertEquals(2, outbox.inFlight());
        assertEquals(3, outbox.take(10, 1000).size());
    }

    @Test
    void entriesSurviveARestartWithTheirKeys() {
        HarvestOutbox outbox = new HarvestOutbox("s");
        String key = outbox.add(ANNA, 3, 7, 5, 1, 1000, 3);
        outbox.take(10, 1000); // in flight when the server stopped
        List<String> saved = outbox.encode();
        HarvestOutbox after = new HarvestOutbox("s");
        List<String> skipped = new ArrayList<>();
        List<String> lines = new ArrayList<>(saved);
        lines.add("broken");
        assertEquals(1, after.load(lines, skipped));
        assertEquals(1, skipped.size());
        List<HarvestOutbox.Entry> batch = after.take(10, 0);
        assertEquals(key, batch.get(0).key());
        assertEquals(3, batch.get(0).breaks());
        assertEquals(1, batch.get(0).fortuneLevel());
    }

    @Test
    void sharesOfOfflinePlayersWaitForThem() {
        OwedPay owed = new OwedPay();
        owed.add(ANNA, 41, 3);
        owed.add(ANNA, 41, 2);
        owed.add(ANNA, 42, 0);
        OwedPay after = new OwedPay();
        after.load(owed.encode());
        assertEquals(Map.of(41, 5L), after.take(ANNA));
        assertEquals(Map.of(), after.take(ANNA));
        assertEquals(Map.of(), after.take(BEN));
    }
}
