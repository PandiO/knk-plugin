package net.knightsandkings.knk.core.survival.checkpoint;

/**
 * When a running survival match writes its checkpoint (DESIGN §10 runtime record; Siege Survival link S8b): on every
 * state change the runtime reports, every {@code intervalMillis} in between (0 = no interval), one request in flight at
 * a time with latest-wins coalescing (a change during a send is sent once the send answers, with the state of that
 * moment), and a failure log at most once a minute. Pure bookkeeping; the caller supplies the clock.
 */
public final class CheckpointCadence {

    /** How often a failing checkpoint is logged (once a minute, not per attempt). */
    public static final long FAILURE_LOG_INTERVAL_MILLIS = 60_000L;

    private final long intervalMillis;
    private long lastRequestedMillis = Long.MIN_VALUE;
    private boolean inFlight;
    private boolean pending;
    private long lastFailureLogMillis = Long.MIN_VALUE;
    private int failures;
    private int suppressedFailures;

    public CheckpointCadence(long intervalMillis) {
        this.intervalMillis = Math.max(0L, intervalMillis);
    }

    public long intervalMillis() {
        return intervalMillis;
    }

    /** True when the periodic checkpoint is on and {@code intervalMillis} passed since the last request. */
    public boolean intervalDue(long nowMillis) {
        if (intervalMillis <= 0) return false;
        return lastRequestedMillis == Long.MIN_VALUE || nowMillis - lastRequestedMillis >= intervalMillis;
    }

    /**
     * A checkpoint is wanted now. Returns true when the caller must send it; false when one is in flight (it will be
     * sent, with the then-current state, when {@link #sendCompleted} returns true).
     */
    public boolean request(long nowMillis) {
        lastRequestedMillis = nowMillis;
        if (inFlight) {
            pending = true;
            return false;
        }
        inFlight = true;
        return true;
    }

    /**
     * The in-flight send answered (success or failure). Returns true when a request arrived meanwhile: the caller
     * sends again right away (the new send is already marked in flight).
     */
    public boolean sendCompleted() {
        if (!inFlight) return false;
        if (pending) {
            pending = false;
            return true; // stays in flight for the follow-up send
        }
        inFlight = false;
        return false;
    }

    public boolean inFlight() {
        return inFlight;
    }

    /** A send failed: counts it; true when it is time to log (the first failure, then once a minute). */
    public boolean failed(long nowMillis) {
        failures++;
        if (lastFailureLogMillis == Long.MIN_VALUE || nowMillis - lastFailureLogMillis >= FAILURE_LOG_INTERVAL_MILLIS) {
            lastFailureLogMillis = nowMillis;
            suppressedFailures = 0;
            return true;
        }
        suppressedFailures++;
        return false;
    }

    public int failures() {
        return failures;
    }

    /** Failures since the last logged one (for the next log line). */
    public int suppressedFailures() {
        return suppressedFailures;
    }
}
