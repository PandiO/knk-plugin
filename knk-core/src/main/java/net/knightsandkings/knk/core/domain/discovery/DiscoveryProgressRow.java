package net.knightsandkings.knk.core.domain.discovery;

import java.time.OffsetDateTime;

/**
 * One discoverable domain and whether the player has found it (knk-web-api's
 * DiscoveryProgressRowDto). The amounts are what the discovery paid; 0 while undiscovered.
 */
public record DiscoveryProgressRow(
    int domainId,
    String name,
    String domainType,
    String parentName,
    boolean discovered,
    OffsetDateTime discoveredAt,
    int coins,
    int gems,
    int exp
) {}
