package net.knightsandkings.knk.core.domain.discovery;

/**
 * One newly discovered domain in a {@link DiscoveryGrantResult}. Mirrors knk-web-api's
 * DiscoveryGrantDto: the amounts credited after the player's multipliers, and the same amounts
 * before them ({@code *Base}).
 *
 * @param domainType Town, District, Structure or GateStructure
 * @param parentName the Town a District or Structure lies in; null for a Town
 * @param source     RegionEnter, JoinInside, Replay, or Ancestor when it came with a child
 */
public record DiscoveryGrant(
    int domainId,
    String wgRegionId,
    String name,
    String domainType,
    String parentName,
    String source,
    int coins,
    int gems,
    int exp,
    int coinsBase,
    int gemsBase,
    int expBase
) {}
