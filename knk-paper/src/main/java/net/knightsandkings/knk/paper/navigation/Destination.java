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
 * @param regionId the WorldGuard region id (REGION); for a POINT that is a domain's spawn Location asked for
 *                 without {@code spawn}, the domain's region: standing in it is "already there" (live test
 *                 2026-10-08, N9); else null
 * @param streetId the street id (STREET), else -1
 * @param nodeId   the road node id (NODE), else -1
 * @param arrivalRadius for a POINT, the radius of a landmark's {@code Radius} extent (off-road phase 5.1,
 *                 PandiO/knk-workspace#708): inside that cylinder around the point the player has arrived; null for
 *                 the usual {@code arrive-distance}
 */
public record Destination(String name, Kind kind, String world, double[] point, String regionId, int streetId, int nodeId,
                          Double arrivalRadius) {

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
        if (arrivalRadius != null && !(arrivalRadius > 0 && Double.isFinite(arrivalRadius))) {
            arrivalRadius = null;
        }
    }

    /** The record without an arrival radius (every destination before #708). */
    public Destination(String name, Kind kind, String world, double[] point, String regionId, int streetId, int nodeId) {
        this(name, kind, world, point, regionId, streetId, nodeId, null);
    }

    /** The same destination arrived at within {@code radius} blocks of its point (a POINT only; null: the usual distance). */
    public Destination withArrivalRadius(Double radius) {
        return new Destination(name, kind, world, point, regionId, streetId, nodeId, kind == Kind.POINT ? radius : null);
    }

    /**
     * Whether feet at ({@code x}, {@code y}, {@code z}) are inside the arrival cylinder (#708): within the radius
     * across and as many blocks up or down; false without a radius.
     */
    public boolean withinArrivalRadius(double x, double y, double z) {
        return arrivalRadius != null && point != null
            && net.knightsandkings.knk.core.landmarks.LandmarkExtent.Cylinder.contains(point[0], point[1], point[2],
                arrivalRadius, x, y, z);
    }

    public static Destination point(String name, String world, double x, double y, double z) {
        return new Destination(name, Kind.POINT, world, new double[] {x, y, z}, null, -1, -1);
    }

    /**
     * A domain's spawn Location as the default destination: routed to that point, but a player already in
     * the domain's region is already there.
     */
    public static Destination domainPoint(String name, String world, double x, double y, double z, String regionId) {
        return new Destination(name, Kind.POINT, world, new double[] {x, y, z}, regionId, -1, -1);
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

    /**
     * Whether this is the same place as {@code other}: the kind, name, world and coordinates or ids (the record's own
     * {@code equals} compares the point array by reference).
     */
    public boolean sameAs(Destination other) {
        return other != null && kind == other.kind && name.equals(other.name) && world.equalsIgnoreCase(other.world)
            && java.util.Arrays.equals(point, other.point) && streetId == other.streetId && nodeId == other.nodeId
            && Objects.equals(regionId, other.regionId) && Objects.equals(arrivalRadius, other.arrivalRadius);
    }

    @Override
    public String toString() {
        return kind + " " + name + " in " + world;
    }
}
