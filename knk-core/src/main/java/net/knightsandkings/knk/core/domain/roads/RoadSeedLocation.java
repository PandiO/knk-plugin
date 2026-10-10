package net.knightsandkings.knk.core.domain.roads;

import java.util.Objects;

/**
 * A domain Location inside a box, as {@code GET api/road-network/seed-locations} lists them (plan
 * D12): the builder seeds a tile from the Locations that have a road span within 8 blocks.
 * Mirrors the web-api's {@code RoadSeedLocationDto}. Bukkit-free.
 *
 * @param domainId   the Domain (town, district, structure) the Location belongs to
 * @param domainType the domain's type name as the API reports it
 * @param name       the domain's name
 * @param x          location block x
 * @param y          location block y
 * @param z          location block z
 */
public record RoadSeedLocation(int domainId, String domainType, String name, int x, int y, int z) {
    public RoadSeedLocation {
        Objects.requireNonNull(domainType, "domainType");
        Objects.requireNonNull(name, "name");
    }
}
