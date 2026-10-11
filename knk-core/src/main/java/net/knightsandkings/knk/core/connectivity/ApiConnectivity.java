package net.knightsandkings.knk.core.connectivity;

import java.time.Instant;
import java.util.Optional;

/**
 * The plugin's single up/down view of knk-web-api (KNG-115): a hysteresis state machine fed by
 * readiness probe results.
 * <ul>
 *   <li>Starts {@link ApiConnectivityState#UNKNOWN}; the first probe result decides UP or DOWN
 *       at once, so the server doesn't sit in UNKNOWN for several intervals after a restart.</li>
 *   <li>UP goes DOWN after {@code failuresToDown} consecutive failures.</li>
 *   <li>DOWN goes UP after {@code successesToUp} consecutive successes.</li>
 * </ul>
 * A result that doesn't change the state returns no transition, so callers fire an event only on
 * a real change. Thread-safe; it does no I/O and knows nothing about Bukkit.
 */
public final class ApiConnectivity {

    /** Read-only view for status output such as {@code /knk health}. */
    public record Snapshot(
        ApiConnectivityState state,
        Instant since,
        int consecutiveFailures,
        int consecutiveSuccesses,
        Instant lastProbeAt,
        boolean lastProbeSucceeded,
        String lastProbeDetail
    ) {}

    private final int failuresToDown;
    private final int successesToUp;

    private ApiConnectivityState state = ApiConnectivityState.UNKNOWN;
    private Instant since;
    private int consecutiveFailures;
    private int consecutiveSuccesses;
    private Instant lastProbeAt;
    private boolean lastProbeSucceeded;
    private String lastProbeDetail;

    public ApiConnectivity(int failuresToDown, int successesToUp, Instant now) {
        if (failuresToDown < 1) {
            throw new IllegalArgumentException("failuresToDown must be at least 1");
        }
        if (successesToUp < 1) {
            throw new IllegalArgumentException("successesToUp must be at least 1");
        }
        this.failuresToDown = failuresToDown;
        this.successesToUp = successesToUp;
        this.since = now;
    }

    /** Records a passing probe; returns the transition when this one changed the state. */
    public synchronized Optional<ApiConnectivityTransition> recordSuccess(Instant at, String detail) {
        consecutiveSuccesses++;
        consecutiveFailures = 0;
        noteProbe(at, true, detail);
        if (state == ApiConnectivityState.UP) {
            return Optional.empty();
        }
        if (state == ApiConnectivityState.UNKNOWN || consecutiveSuccesses >= successesToUp) {
            return Optional.of(moveTo(ApiConnectivityState.UP, at, detail));
        }
        return Optional.empty();
    }

    /** Records a failing probe; returns the transition when this one changed the state. */
    public synchronized Optional<ApiConnectivityTransition> recordFailure(Instant at, String detail) {
        consecutiveFailures++;
        consecutiveSuccesses = 0;
        noteProbe(at, false, detail);
        if (state == ApiConnectivityState.DOWN) {
            return Optional.empty();
        }
        if (state == ApiConnectivityState.UNKNOWN || consecutiveFailures >= failuresToDown) {
            return Optional.of(moveTo(ApiConnectivityState.DOWN, at, detail));
        }
        return Optional.empty();
    }

    public synchronized ApiConnectivityState state() {
        return state;
    }

    /** True only while the state is UP; UNKNOWN and DOWN both read as "not reachable". */
    public synchronized boolean isUp() {
        return state == ApiConnectivityState.UP;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(state, since, consecutiveFailures, consecutiveSuccesses,
            lastProbeAt, lastProbeSucceeded, lastProbeDetail);
    }

    public int failuresToDown() {
        return failuresToDown;
    }

    public int successesToUp() {
        return successesToUp;
    }

    private void noteProbe(Instant at, boolean succeeded, String detail) {
        lastProbeAt = at;
        lastProbeSucceeded = succeeded;
        lastProbeDetail = detail;
    }

    private ApiConnectivityTransition moveTo(ApiConnectivityState next, Instant at, String reason) {
        ApiConnectivityTransition transition = new ApiConnectivityTransition(state, next, at, reason);
        state = next;
        since = at;
        return transition;
    }
}
