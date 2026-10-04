package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * What one tile build produces — the plugin-side twin of the web-api's {@code RoadTileGraphUpsertDto}
 * (field names one to one; Phase 2e maps it). Pure data: domain and region tagging need WorldGuard
 * and are added by the paper job afterwards, so {@code domainIds}/{@code regionIds} are empty here.
 *
 * @param builderVersion {@link TileBuilder#BUILDER_VERSION}
 * @param cellCount      spans in the mask (tile + margin)
 * @param levelCount     most spans stacked in one column
 * @param nodes          nodes inside the tile
 * @param edges          edges inside the tile (no cross-tile edges — the API stitches boundary nodes)
 * @param warnings       cell cap, unmatched seeds, anchors off the road …
 * @param corrections    what each admin correction did in this build (not uploaded; for the build summary)
 */
public record TileBuildResult(int builderVersion, int cellCount, int levelCount, List<Node> nodes,
                              List<Edge> edges, List<BuildWarning> warnings, List<Correction> corrections) {

    /** The kind of an admin correction the builder applies. */
    public enum CorrectionKind {
        /** A Pruned tombstone: leave out the nearest dead end ({@code /knk road node prune}). */
        PRUNED_DEAD_END,
        /** A PrunedEdge tombstone: leave out the chain passing nearest ({@code /knk road edge prune}). */
        PRUNED_EDGE,
        /** An admin Anchor node. */
        ANCHOR,
        /** A designed plaza centre (rev. 5). */
        PLAZA
    }

    /**
     * What one admin correction did in this build (smoke test 2026-10-04, finding L): every prune
     * tombstone, anchor and designed plaza, whether it found something to act on ({@code applied}) and
     * what ({@code detail}). Corrections are rules replayed on every rebuild; one that matches nothing
     * any more is stale, and one that acts far from its own position may hit something new.
     */
    public record Correction(int nodeId, CorrectionKind kind, boolean applied, String detail, int x, int y, int z) {
        public Correction {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(detail, "detail");
        }
    }

    /** A result without correction notes. */
    public TileBuildResult(int builderVersion, int cellCount, int levelCount, List<Node> nodes,
                           List<Edge> edges, List<BuildWarning> warnings) {
        this(builderVersion, cellCount, levelCount, nodes, edges, warnings, List.of());
    }

    /** Mirrors {@code RoadTileGraphNodeDto}: {@code key}, {@code existingId}, {@code x}, {@code y}, {@code z}, {@code kind}. */
    public record Node(String key, OptionalInt existingId, int x, int y, int z, RoadNodeKind kind) {
        public Node {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(existingId, "existingId");
            Objects.requireNonNull(kind, "kind");
            if (key.startsWith("id:")) {
                throw new IllegalArgumentException("node keys must not start with \"id:\" (reserved by the API)");
            }
        }
    }

    /**
     * Mirrors {@code RoadTileGraphEdgeDto}: {@code existingId}, {@code fromKey}, {@code toKey},
     * {@code geometry} (RDP polyline of floor positions, from → to, ending exactly on the nodes),
     * {@code length} (walked 3D length of the unsimplified centreline), {@code avgWidth},
     * {@code profileId}, {@code gateDoorIds}, {@code domainIds}, {@code regionIds}.
     */
    public record Edge(OptionalInt existingId, String fromKey, String toKey, List<int[]> geometry, double length,
                       double avgWidth, OptionalInt profileId, List<Integer> gateDoorIds, List<Integer> domainIds,
                       List<String> regionIds) {
        public Edge {
            Objects.requireNonNull(existingId, "existingId");
            Objects.requireNonNull(fromKey, "fromKey");
            Objects.requireNonNull(toKey, "toKey");
            Objects.requireNonNull(profileId, "profileId");
            geometry = List.copyOf(Objects.requireNonNull(geometry, "geometry"));
            gateDoorIds = List.copyOf(Objects.requireNonNull(gateDoorIds, "gateDoorIds"));
            domainIds = List.copyOf(Objects.requireNonNull(domainIds, "domainIds"));
            regionIds = List.copyOf(Objects.requireNonNull(regionIds, "regionIds"));
            if (geometry.size() < 2) {
                throw new IllegalArgumentException("an edge needs at least two geometry points");
            }
        }

        /** Straight-line distance between the geometry's ends. */
        public double chord() {
            int[] a = geometry.get(0);
            int[] b = geometry.get(geometry.size() - 1);
            double dx = a[0] - b[0];
            double dy = a[1] - b[1];
            double dz = a[2] - b[2];
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public TileBuildResult {
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        corrections = List.copyOf(Objects.requireNonNull(corrections, "corrections"));
    }

    /** Warnings as the API's {@code warnings[]} strings. */
    public List<String> warningTexts() {
        return warnings.stream().map(BuildWarning::text).toList();
    }

    public Optional<Node> node(String key) {
        return nodes.stream().filter(n -> n.key().equals(key)).findFirst();
    }

    /** Nodes of one kind. */
    public List<Node> nodes(RoadNodeKind kind) {
        return nodes.stream().filter(n -> n.kind() == kind).toList();
    }

    /** Edges touching a node key. */
    public List<Edge> edgesOf(String key) {
        return edges.stream().filter(e -> e.fromKey().equals(key) || e.toKey().equals(key)).toList();
    }
}
