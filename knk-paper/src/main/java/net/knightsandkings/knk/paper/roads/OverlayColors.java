package net.knightsandkings.knk.paper.roads;

import java.util.Optional;

import net.knightsandkings.knk.core.domain.roads.RoadEdge;
import net.knightsandkings.knk.core.domain.roads.RoadEdgeFlag;
import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;

/**
 * Colours of the road overlay (DESIGN §7: edges coloured by street, unlabelled grey, stale orange,
 * closed red, gate crossings marked; nodes by kind). Pure {@code 0xRRGGBB} ints so the choice is
 * testable without Bukkit; {@code RoadOverlayRenderer} turns them into dust options.
 */
public final class OverlayColors {
    public static final int UNLABELLED = 0x9E9E9E;
    public static final int STALE = 0xFF8C00;
    public static final int CLOSED = 0xE53935;
    public static final int NO_GPS = 0x7E57C2;
    public static final int RECORDED_TINT = 0x40C4FF;
    public static final int GATE_MARKER = 0xFFEB3B;
    public static final int JUNCTION = 0xFFFFFF;
    public static final int ENDPOINT = 0x00E5FF;
    public static final int BOUNDARY = 0x8D6E63;
    public static final int ANCHOR = 0xFF4081;
    public static final int NAMED_NODE = 0x69F0AE;
    public static final int PRUNED = 0x5D4037;
    /** Proposal items of curated tiles (plan §5.7): added, removed, changed or moved. */
    public static final int PROPOSAL_ADDED = 0x00E676;
    public static final int PROPOSAL_REMOVED = 0xFF1744;
    public static final int PROPOSAL_CHANGED = 0xFFEA00;

    /** Distinct, saturated street hues; a street keeps its colour across sessions (a stride over its id). */
    private static final int[] STREET_PALETTE = {
        0x2979FF, 0x00C853, 0xFFD600, 0xD500F9, 0x00B8D4, 0xFF6D00, 0x64DD17, 0xF50057,
        0x3D5AFE, 0x1DE9B6, 0xC6FF00, 0xAA00FF, 0x0091EA, 0xFFAB00, 0x76FF03, 0xFF1744
    };

    private OverlayColors() {
    }

    /** The edge's colour: closed, stale and no-gps states first, then the street colour, else grey. */
    public static int edge(RoadEdge edge) {
        if (edge.hasFlag(RoadEdgeFlag.CLOSED)) {
            return CLOSED;
        }
        if (edge.stale()) {
            return STALE;
        }
        if (edge.hasFlag(RoadEdgeFlag.NO_GPS)) {
            return NO_GPS;
        }
        if (edge.streetId().isEmpty()) {
            return UNLABELLED;
        }
        return street(edge.streetId().getAsInt());
    }

    /** A stable colour for a street id; neighbouring ids (streets created together) get different hues. */
    public static int street(int streetId) {
        return STREET_PALETTE[Math.floorMod(streetId * 7, STREET_PALETTE.length)];
    }

    public static int node(RoadNodeKind kind, boolean named) {
        if (named) {
            return NAMED_NODE;
        }
        return switch (kind) {
            case JUNCTION -> JUNCTION;
            case ENDPOINT -> ENDPOINT;
            case BOUNDARY, SPLIT -> BOUNDARY;
            case ANCHOR -> ANCHOR;
            case PRUNED, PRUNED_EDGE -> PRUNED;
        };
    }

    /** Whether the edge gets the gate marker (it crosses at least one gate door). */
    public static boolean hasGateMarker(RoadEdge edge) {
        return !edge.gateDoorIds().isEmpty();
    }

    /** Which status word the legend shows for an edge, if any. */
    public static Optional<String> status(RoadEdge edge) {
        if (edge.hasFlag(RoadEdgeFlag.CLOSED)) {
            return Optional.of("closed");
        }
        if (edge.stale()) {
            return Optional.of("stale");
        }
        if (edge.hasFlag(RoadEdgeFlag.NO_GPS)) {
            return Optional.of("no-gps");
        }
        return Optional.empty();
    }

    public static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    public static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    public static int blue(int rgb) {
        return rgb & 0xFF;
    }
}
