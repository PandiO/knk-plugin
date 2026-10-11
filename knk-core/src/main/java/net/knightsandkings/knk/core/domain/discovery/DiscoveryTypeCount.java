package net.knightsandkings.knk.core.domain.discovery;

/**
 * Discovered vs. total enabled domains of one type. {@code enabled}: the type's reward rule is on; a
 * disabled type (total 0 unless overrides enable some domains) is tagged for staff and left out for players.
 */
public record DiscoveryTypeCount(String domainType, int discovered, int total, boolean enabled) {

    public DiscoveryTypeCount(String domainType, int discovered, int total) {
        this(domainType, discovered, total, true);
    }
}
