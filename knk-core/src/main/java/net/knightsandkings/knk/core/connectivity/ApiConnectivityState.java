package net.knightsandkings.knk.core.connectivity;

/** Service-wide reachability of knk-web-api as seen by this game server (KNG-115). */
public enum ApiConnectivityState {
    /** No probe has completed yet (plugin just enabled). */
    UNKNOWN,
    /** The readiness probe passes: the API and its database answer. */
    UP,
    /** The readiness probe failed {@code failuresToDown} times in a row. */
    DOWN
}
