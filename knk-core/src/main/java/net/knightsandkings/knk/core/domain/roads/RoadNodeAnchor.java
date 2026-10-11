package net.knightsandkings.knk.core.domain.roads;

import java.util.Objects;

/**
 * The body of {@code POST api/road-nodes/anchor}: an admin-placed Anchor node (DESIGN §5.10) the
 * builder must keep and the API creates as {@code Manual} + {@code Locked} (Phase 1 decision 7).
 * Mirrors the web-api's {@code RoadNodeAnchorDto}. Bukkit-free.
 *
 * @param world Bukkit world name
 * @param x     floor block x
 * @param y     floor block y
 * @param z     floor block z
 * @param name  display name (a {@code /navigate} destination), or {@code null}
 */
public record RoadNodeAnchor(String world, int x, int y, int z, String name) {
    public RoadNodeAnchor {
        Objects.requireNonNull(world, "world");
    }
}
