package net.knightsandkings.knk.core.domain.roads;

import java.util.Objects;
import java.util.Optional;

/**
 * A node of the downloaded road network (DESIGN §3.5) as the router sees it. Bukkit-free.
 *
 * <p>Coordinates are the <b>floor block</b> the node stands on (Phase 2c decision 1): a player
 * standing there has feet at {@code y + 1}.
 *
 * @param id          the API id (stable across rebuilds); never negative - the router uses negative
 *                    ids for its virtual nodes
 * @param x           floor block x
 * @param y           floor block y
 * @param z           floor block z
 * @param kind        junction, endpoint, tile boundary or admin anchor
 * @param name        the admin-given name, or {@code null}; a named node is a {@code /navigate}
 *                    destination
 * @param componentId connected-component id computed by the API (DESIGN §5.8); routing refuses
 *                    fast when start and goal differ
 * @param locked      an admin locked the node's position (Phase 1 decision 7): the builder must
 *                    keep it where it is ({@code NodeMatcher.PreviousNode.locked}). Phase 2e added
 *                    this field from the API's {@code RoadNodeDto}; the router ignores it
 * @param plazaRadius radius of the designed plaza this node is the centre of, or {@code 0} when it is
 *                    none (DESIGN §5.6 step 4, rev. 5); the builder makes that footprint one junction
 *                    on this node
 */
public record RoadNode(int id, int x, int y, int z, RoadNodeKind kind, String name, int componentId, boolean locked,
                       int plazaRadius) {

    public RoadNode {
        if (id < 0) {
            throw new IllegalArgumentException("node ids must be >= 0 (negative ids are the router's virtual nodes)");
        }
        Objects.requireNonNull(kind, "kind");
        if (name != null && name.isBlank()) {
            name = null;
        }
        if (plazaRadius < 0) {
            throw new IllegalArgumentException("plazaRadius must be >= 0 (0 = no plaza)");
        }
    }

    /** A node that is no plaza centre. */
    public RoadNode(int id, int x, int y, int z, RoadNodeKind kind, String name, int componentId, boolean locked) {
        this(id, x, y, z, kind, name, componentId, locked, 0);
    }

    /** Whether this node is the centre of a designed plaza. */
    public boolean isPlazaCentre() {
        return plazaRadius > 0;
    }

    /** An unlocked node (what the router and its fixtures build). */
    public RoadNode(int id, int x, int y, int z, RoadNodeKind kind, String name, int componentId) {
        this(id, x, y, z, kind, name, componentId, false);
    }

    /** The name, when the node has one. */
    public Optional<String> nameOptional() {
        return Optional.ofNullable(name);
    }

    /** Whether this node is a named destination (an Anchor or any other node an admin named). */
    public boolean isDestination() {
        return name != null;
    }

    /** Position as {@code {x, y, z}} doubles (floor block). */
    public double[] position() {
        return new double[] {x, y, z};
    }

    /** Euclidean distance from this node's floor block to a point. */
    public double distanceTo(double px, double py, double pz) {
        double dx = px - x;
        double dy = py - y;
        double dz = pz - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
