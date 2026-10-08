package net.knightsandkings.knk.core.domain.roads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * The body of {@code POST api/road-edges}: an admin-walked Recorded edge (DESIGN §5.10) that
 * survives rebuilds. The API snaps both ends to the nearest node within 3 blocks or creates an
 * Anchor there, and owns the edge by the tile of its first point (Phase 1 decision 8). Mirrors
 * the web-api's {@code RoadEdgeRecordDto}. Bukkit-free.
 *
 * @param world       Bukkit world name
 * @param geometry    the walked polyline as {@code {x, y, z}} floor blocks, at least two points
 * @param length      walked length; empty = the API uses the polyline length
 * @param avgWidth    average width the admin declares (default 1)
 * @param profileId   profile to assign, if any
 * @param streetId    street to label with, if any ({@code Manual})
 * @param gateDoorIds gate doors the edge passes through
 * @param domainIds   domains the edge crosses (Phase 3 tags these)
 * @param regionIds   WorldGuard region ids the edge crosses (plan D11)
 */
public record RoadEdgeRecord(String world, List<int[]> geometry, OptionalDouble length, double avgWidth,
                             OptionalInt profileId, OptionalInt streetId, List<Integer> gateDoorIds,
                             List<Integer> domainIds, List<String> regionIds) {
    public RoadEdgeRecord {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(length, "length");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(streetId, "streetId");
        if (geometry.size() < 2) {
            throw new IllegalArgumentException("a recorded edge needs at least two geometry points");
        }
        List<int[]> copy = new ArrayList<>(geometry.size());
        for (int[] p : geometry) {
            if (p == null || p.length != 3) {
                throw new IllegalArgumentException("geometry points must be {x, y, z}");
            }
            copy.add(p.clone());
        }
        geometry = Collections.unmodifiableList(copy);
        if (!(avgWidth > 0)) {
            throw new IllegalArgumentException("avgWidth must be > 0");
        }
        gateDoorIds = List.copyOf(Objects.requireNonNull(gateDoorIds, "gateDoorIds"));
        domainIds = List.copyOf(Objects.requireNonNull(domainIds, "domainIds"));
        regionIds = List.copyOf(Objects.requireNonNull(regionIds, "regionIds"));
    }
}
