package net.knightsandkings.knk.core.connectivity;

import java.time.Instant;
import java.util.Objects;

/**
 * One change of {@link ApiConnectivityState}. {@code from} is {@link ApiConnectivityState#UNKNOWN}
 * only for the first probe after enable; subscribers that react to a recovery should check
 * {@link #isRecovery()} (DOWN to UP) rather than "to == UP".
 *
 * @param reason the last probe's outcome, e.g. "healthy" or "ConnectException: ..."
 */
public record ApiConnectivityTransition(
    ApiConnectivityState from,
    ApiConnectivityState to,
    Instant at,
    String reason
) {
    public ApiConnectivityTransition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(at, "at");
    }

    /** The API came back after an outage (DOWN to UP). */
    public boolean isRecovery() {
        return from == ApiConnectivityState.DOWN && to == ApiConnectivityState.UP;
    }

    /** The API was lost (UP or UNKNOWN to DOWN). */
    public boolean isOutage() {
        return to == ApiConnectivityState.DOWN;
    }
}
