package net.knightsandkings.knk.core.realtime;

/**
 * Checks the per-connection {@code seq} of realtime envelopes (DESIGN §4.2, P2): the API numbers the messages of each
 * connection 1, 2, 3, ... A jump means messages were lost (a dropped queue entry or a failed send), and the receiver
 * runs its full resync. A repeat or an older number is ignored. {@link #reset()} on every new connection.
 * Thread-safe.
 */
public final class SequenceTracker {

    public enum Outcome {
        /** The next number in order (or the first one, 1). */
        IN_ORDER,
        /** Numbers were skipped: resync. */
        GAP,
        /** Seen before or older: ignore the message. */
        DUPLICATE
    }

    private long last;

    public synchronized Outcome observe(long seq) {
        if (seq <= last) {
            return Outcome.DUPLICATE;
        }
        long expected = last + 1;
        last = seq;
        return seq == expected ? Outcome.IN_ORDER : Outcome.GAP;
    }

    /** The highest number seen on this connection (0 before the first). */
    public synchronized long last() {
        return last;
    }

    public synchronized void reset() {
        last = 0;
    }
}
