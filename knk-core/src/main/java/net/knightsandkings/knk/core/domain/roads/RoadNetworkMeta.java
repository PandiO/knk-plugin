package net.knightsandkings.knk.core.domain.roads;

import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.core.roads.route.RoadNetworkSnapshot;

/**
 * {@code GET api/road-network/meta?world=}: everything a world's snapshot needs besides the tile
 * graphs (Phase 2d status → 2e). Mirrors the web-api's {@code RoadNetworkMetaDto}. Bukkit-free.
 *
 * @param profiles   every profile (the api-client's {@code RoadMapper.toSnapshotProfile} reduces
 *                   one to the router's {@code RoadNetworkSnapshot.Profile})
 * @param streets    the streets some edge of the world is labelled with (Phase 1 decision 12),
 *                   already in the snapshot's record
 * @param components the world's connected components with their node counts
 */
public record RoadNetworkMeta(List<RoadProfile> profiles, List<RoadNetworkSnapshot.Street> streets,
                              List<RoadComponent> components) {
    public RoadNetworkMeta {
        profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
        streets = List.copyOf(Objects.requireNonNull(streets, "streets"));
        components = List.copyOf(Objects.requireNonNull(components, "components"));
    }
}
