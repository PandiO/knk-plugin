package net.knightsandkings.knk.core.domain.discovery;

/**
 * An id the server did not grant, with why (knk-web-api's DiscoverySkipDto).
 *
 * @param key    the region id as sent, or a domain id as a string (ancestors)
 * @param reason {@link #NOT_A_DOMAIN}, {@link #DISABLED} or {@link #RATE_LIMITED}
 */
public record DiscoverySkip(String key, String reason) {
    public static final String NOT_A_DOMAIN = "NotADomain";
    public static final String DISABLED = "Disabled";
    public static final String RATE_LIMITED = "RateLimited";
}
