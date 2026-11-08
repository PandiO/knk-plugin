package net.knightsandkings.knk.core.survival.checkpoint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGN §10 runtime record (link S8b): interval, one in flight with latest-wins, failure log once a minute. */
class CheckpointCadenceTest {

    @Test
    void theIntervalIsDueAtStartAndEveryIntervalAfterARequest() {
        CheckpointCadence c = new CheckpointCadence(30_000);
        assertTrue(c.intervalDue(1_000));
        assertTrue(c.request(1_000));
        c.sendCompleted();
        assertFalse(c.intervalDue(30_999));
        assertTrue(c.intervalDue(31_000));
    }

    @Test
    void zeroTurnsTheIntervalOffButStateChangesStillSend() {
        CheckpointCadence c = new CheckpointCadence(0);
        assertFalse(c.intervalDue(1_000_000));
        assertTrue(c.request(5));
        assertEquals(0, c.intervalMillis());
    }

    @Test
    void oneSendInFlightAndTheLatestRequestFollowsOnce() {
        CheckpointCadence c = new CheckpointCadence(30_000);
        assertTrue(c.request(1_000), "nothing in flight: send");
        assertFalse(c.request(1_100), "in flight: coalesced");
        assertFalse(c.request(1_200), "in flight: coalesced again");
        assertTrue(c.inFlight());
        assertTrue(c.sendCompleted(), "one follow-up send for the two coalesced requests");
        assertTrue(c.inFlight(), "the follow-up is in flight now");
        assertFalse(c.sendCompleted(), "nothing pending");
        assertFalse(c.inFlight());
        assertFalse(c.sendCompleted(), "idempotent when idle");
    }

    @Test
    void failuresAreLoggedOnceAMinute() {
        CheckpointCadence c = new CheckpointCadence(30_000);
        assertTrue(c.failed(0), "the first failure is logged");
        assertFalse(c.failed(10_000));
        assertFalse(c.failed(59_999));
        assertEquals(2, c.suppressedFailures());
        assertTrue(c.failed(60_000), "a minute later: logged again");
        assertEquals(0, c.suppressedFailures());
        assertEquals(4, c.failures());
    }
}
