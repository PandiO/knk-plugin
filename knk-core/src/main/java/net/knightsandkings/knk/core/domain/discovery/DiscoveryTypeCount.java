package net.knightsandkings.knk.core.domain.discovery;

/** Discovered vs. total enabled domains of one type. */
public record DiscoveryTypeCount(String domainType, int discovered, int total) {}
