package net.knightsandkings.knk.core.domain.roads;

/**
 * Kind of a road node (DESIGN §2, §3.5). Mirrors the web-api enum {@code RoadNodeKind}; serialised by
 * name. {@code Virtual} nodes exist only at routing time and are never stored, so they are not here.
 */
public enum RoadNodeKind {
    /** Detected: three or more branches meet, or a plaza collapsed into one node. */
    JUNCTION,
    /** Detected: a dead end or the end of the road. */
    ENDPOINT,
    /** The road crosses the tile border here; the API stitches it to the neighbour tile's node (plan D7). */
    BOUNDARY,
    /** Placed by an admin; chains are split at it and it survives rebuilds. */
    ANCHOR,
    /**
     * A tombstone: an admin pruned the dead end that ended here ({@code /knk road node prune}). It has
     * no edges; the builder leaves the arm ending near it out of every later build until it is unpruned.
     */
    PRUNED,
    /**
     * A tombstone on the middle of a pruned edge ({@code /knk road edge prune}, or a junction's edges
     * with {@code /knk road node prune}). It has no edges; the builder leaves the chain passing nearest
     * it out of every later build until it is unpruned.
     */
    PRUNED_EDGE,
    /**
     * Routing time only, never stored (rev. 7 Part A, REV7_PROPOSAL §2): where the routing view cuts a stored
     * edge because access changes there - at a gate door or a region border. No destination, no name; plumbing
     * like {@link #BOUNDARY} for instructions.
     */
    SPLIT;

    /** Whether this is a tombstone ({@link #PRUNED} or {@link #PRUNED_EDGE}): no edges, never matched or routed. */
    public boolean isTombstone() {
        return this == PRUNED || this == PRUNED_EDGE;
    }

    /** The name the web-api uses ({@code Junction}, {@code Endpoint}, ..., {@code Pruned}, {@code PrunedEdge}). */
    public String apiName() {
        StringBuilder out = new StringBuilder();
        for (String word : name().toLowerCase().split("_")) {
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /** Inverse of {@link #apiName()}; case-insensitive. */
    public static RoadNodeKind fromApiName(String apiName) {
        String wanted = apiName.trim();
        for (RoadNodeKind kind : values()) {
            if (kind.apiName().equalsIgnoreCase(wanted)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown road node kind '" + apiName + "'");
    }
}
