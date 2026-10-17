package net.knightsandkings.knk.core.connectivity;

/**
 * Tells the data-access layer that knk-web-api is known to be down, so reads skip the API instead
 * of waiting out a failed call and its retries (KNG-171). The plugin backs it with the service-wide
 * {@link ApiConnectivity} state; core code only sees this port, never Bukkit.
 * <p>
 * Only a confirmed DOWN counts: UNKNOWN (no probe yet, or the probe is switched off) and UP both
 * read as "not down", so calls behave exactly as they did before KNG-171.
 */
@FunctionalInterface
public interface ApiOutageSignal {

    /** Never down: every call goes to the API (tests, gateways built without a signal). */
    ApiOutageSignal NEVER = () -> false;

    /** True while calls to knk-web-api should be skipped. Must be cheap and must not do I/O. */
    boolean isApiDown();
}
