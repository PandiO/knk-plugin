package net.knightsandkings.knk.core.domain.roads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * An edge of the downloaded road network (DESIGN §3.6) as the router sees it. Bukkit-free,
 * immutable (the lists are unmodifiable copies).
 *
 * <p>{@code geometry} is the RDP-simplified 3D polyline from the From node to the To node, every
 * point a <b>floor block</b> position (Phase 2c decision 1); its first and last points are exactly
 * the two nodes' positions. {@code length} is the walked 3D length of the unsimplified centreline,
 * so it is never shorter than the polyline (Phase 2c decision 17). Edges arrive from the API with
 * {@code fromNodeId < toNodeId}; nothing here assumes more than "geometry runs From → To".
 *
 * @param id             the API id
 * @param fromNodeId     From node (geometry start)
 * @param toNodeId       To node (geometry end)
 * @param geometry       polyline, at least two points, each {@code {x, y, z}}
 * @param length         walked 3D length in blocks
 * @param avgWidth       average width from the distance transform (blocks)
 * @param profileId      the best-matching profile (gives the road class), or empty
 * @param streetId       the labelled Street, or empty
 * @param costMultiplier admin tuning factor (default 1.0)
 * @param flags          static admin flags ({@link RoadEdgeFlag})
 * @param gateDoorIds    gate doors whose closed footprint the edge passes, in geometry order
 * @param domainIds      domains (Town/District/Structure) whose regions the edge passes, in order
 * @param regionIds      WorldGuard region ids the edge passes, in order (plan D11) - what the
 *                       router looks domains up by
 * @param source         detected, recorded or a tile stitch
 * @param stale          {@code Status == Stale}: the tile is dirty; still routable
 */
public record RoadEdge(int id, int fromNodeId, int toNodeId, List<int[]> geometry, double length, double avgWidth,
                       OptionalInt profileId, OptionalInt streetId, double costMultiplier, Set<RoadEdgeFlag> flags,
                       List<Integer> gateDoorIds, List<Integer> domainIds, List<String> regionIds,
                       RoadEdgeSource source, boolean stale) {

    public RoadEdge {
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(streetId, "streetId");
        Objects.requireNonNull(flags, "flags");
        Objects.requireNonNull(gateDoorIds, "gateDoorIds");
        Objects.requireNonNull(domainIds, "domainIds");
        Objects.requireNonNull(regionIds, "regionIds");
        Objects.requireNonNull(source, "source");
        if (geometry.size() < 2) {
            throw new IllegalArgumentException("edge " + id + ": geometry needs at least two points");
        }
        List<int[]> copy = new ArrayList<>(geometry.size());
        for (int[] p : geometry) {
            if (p == null || p.length != 3) {
                throw new IllegalArgumentException("edge " + id + ": geometry points must be {x, y, z}");
            }
            copy.add(p.clone());
        }
        geometry = Collections.unmodifiableList(copy);
        if (!(length >= 0)) {
            throw new IllegalArgumentException("edge " + id + ": length must be >= 0");
        }
        if (!(costMultiplier > 0)) {
            throw new IllegalArgumentException("edge " + id + ": costMultiplier must be > 0");
        }
        flags = flags.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(flags));
        gateDoorIds = List.copyOf(gateDoorIds);
        domainIds = List.copyOf(domainIds);
        regionIds = List.copyOf(regionIds);
    }

    public boolean hasFlag(RoadEdgeFlag flag) {
        return flags.contains(flag);
    }

    public boolean isOneway() {
        return flags.contains(RoadEdgeFlag.ONEWAY);
    }

    public boolean isStitch() {
        return source == RoadEdgeSource.STITCH;
    }

    /** The node at the other end of the edge, or {@code -1} when {@code nodeId} is neither end. */
    public int otherNode(int nodeId) {
        if (nodeId == fromNodeId) {
            return toNodeId;
        }
        if (nodeId == toNodeId) {
            return fromNodeId;
        }
        return -1;
    }

    /** Whether the edge may be walked from {@code fromNode} towards the other end (oneway rule). */
    public boolean allowsTravelFrom(int nodeId) {
        if (nodeId == fromNodeId) {
            return true;
        }
        return nodeId == toNodeId && !isOneway();
    }

    /** Whether travelling in this direction is allowed ({@code forward} = From → To). */
    public boolean allowsDirection(boolean forward) {
        return forward || !isOneway();
    }
}
