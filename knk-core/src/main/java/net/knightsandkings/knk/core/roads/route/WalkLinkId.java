package net.knightsandkings.knk.core.roads.route;

/**
 * The id of a walk link (off-road navigation DESIGN § 5.2.3, decision O17): its own id space, disjoint from stored road
 * edge and node ids (0 up to {@link RoutingView#FIRST_SYNTHETIC_ID}), from the routing view's cut pieces and split nodes
 * ({@code 2 000 000 000} up to {@link Integer#MAX_VALUE}) and from the router's negative virtual nodes. A separate type,
 * so a link id can never be passed where an edge id is expected, and a {@code long} whose value is at least
 * {@link #FIRST_VALUE} (2<sup>62</sup>), so even its number lies outside every {@code int} id.
 *
 * <p>A link is directional (a walk path from one road node to another; a drop walked down cannot always be walked up):
 * the id of {@code from → to} differs from the id of {@code to → from}.
 *
 * @param value {@link #FIRST_VALUE} {@code | from << 31 | to}
 */
public record WalkLinkId(long value) implements Comparable<WalkLinkId> {

    /** The smallest value of a walk link id: 2<sup>62</sup>, above every {@code int}. */
    public static final long FIRST_VALUE = 1L << 62;
    private static final long NODE_MASK = (1L << 31) - 1;

    public WalkLinkId {
        if (value < FIRST_VALUE) {
            throw new IllegalArgumentException("not a walk link id: " + value);
        }
    }

    /** The link from road node {@code from} to road node {@code to} (both non-negative node ids of the routing view). */
    public static WalkLinkId of(int from, int to) {
        if (from < 0 || to < 0) {
            throw new IllegalArgumentException("walk links join road nodes (non-negative ids): " + from + " -> " + to);
        }
        if (from == to) {
            throw new IllegalArgumentException("a walk link joins two different nodes: " + from);
        }
        return new WalkLinkId(FIRST_VALUE | ((long) from << 31) | to);
    }

    public int fromNode() {
        return (int) ((value >>> 31) & NODE_MASK);
    }

    public int toNode() {
        return (int) (value & NODE_MASK);
    }

    /** The link the other way round. */
    public WalkLinkId reversed() {
        return of(toNode(), fromNode());
    }

    @Override
    public int compareTo(WalkLinkId other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return "link " + fromNode() + "->" + toNode();
    }
}
