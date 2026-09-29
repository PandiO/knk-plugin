package net.knightsandkings.knk.paper.navigation;

import java.util.Objects;

/**
 * Where a navigation goes (DESIGN §6.1-6.3), resolved from a {@link NavTarget}: a point (a
 * Location or a domain's spawn Location), a WorldGuard region (the closest point of it, §6.3), a
 * labelled street (its nearest point) or a named road node. Coordinates are <b>feet</b> positions
 * (where a player stands); the service converts to floor blocks where the router wants them.
 *
 * @param name     display name for messages
 * @param kind     how the goal set is built
 * @param world    the world the destination is in
 * @param point    feet coordinates (POINT), else null
 * @param regionId the WorldGuard region id (REGION), else null
 * @param streetId the street id (STREET), else -1
 * @param nodeId   the road node id (NODE), else -1
 */
public record Destination(String name, Kind kind, String world, double[] point, String regionId, int streetId, int nodeId) {

    public enum Kind {
        POINT,
        REGION,
        STREET,
        NODE
    }

    public Destination {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(world, "world");
        point = point == null ? null : point.clone();
    }

    public static Destination point(String name, String world, double x, double y, double z) {
        return new Destination(name, Kind.POINT, world, new double[] {x, y, z}, null, -1, -1);
    }

    public static Destination region(String name, String world, String regionId) {
        return new Destination(name, Kind.REGION, world, null, Objects.requireNonNull(regionId, "regionId"), -1, -1);
    }

    public static Destination street(String name, String world, int streetId) {
        return new Destination(name, Kind.STREET, world, null, null, streetId, -1);
    }

    public static Destination node(String name, String world, int nodeId, double[] floorPosition) {
        double[] feet = floorPosition == null ? null : new double[] {floorPosition[0] + 0.5, floorPosition[1] + 1, floorPosition[2] + 0.5};
        return new Destination(name, Kind.NODE, world, feet, null, -1, nodeId);
    }

    @Override
    public String toString() {
        return kind + " " + name + " in " + world;
    }
}
