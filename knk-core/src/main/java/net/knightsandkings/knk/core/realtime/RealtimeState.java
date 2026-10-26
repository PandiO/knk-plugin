package net.knightsandkings.knk.core.realtime;

/** State of the plugin's realtime connection to the API's game hub (PandiO/knk-workspace#178). */
public enum RealtimeState {
    /** {@code realtime.enabled: false} (the default): no connection, behaviour as before. */
    DISABLED,
    /** Not started yet, or stopped (plugin disable). */
    STOPPED,
    /** A connect attempt is running. */
    CONNECTING,
    /** Connected: hub messages arrive. */
    CONNECTED,
    /** Disconnected; the next attempt is scheduled (backoff). */
    WAITING,
    /** Disconnected while the API connectivity probe says DOWN: no attempts until it is UP again. */
    PAUSED
}
