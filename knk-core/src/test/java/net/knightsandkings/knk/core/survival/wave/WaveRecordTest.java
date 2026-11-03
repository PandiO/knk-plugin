package net.knightsandkings.knk.core.survival.wave;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** DESIGN §3: a wipe in wave 10 means reached 10, completed 9. */
class WaveRecordTest {

    @Test
    void wipeInWaveTenMeansReachedTenCompletedNine() {
        WaveRecord record = WaveRecord.NONE;
        for (int round = 1; round <= 9; round++) record = record.started(round).completed(round);
        record = record.started(10);
        assertEquals(new WaveRecord(10, 9), record);
    }

    @Test
    void completedNeverExceedsReached() {
        assertThrows(IllegalArgumentException.class, () -> new WaveRecord(3, 4));
        assertEquals(new WaveRecord(5, 5), WaveRecord.NONE.completed(5));
    }
}
