package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.domain.roads.RoadNodeKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * What a rebuild of a Curated tile would change (rev. 6 Part B, plan §5.7): the numbered items of a
 * {@link TileDiff}, the build's own figures (uploaded with the last accepted items, so the tile then
 * counts as built with {@code builderVersion}) and the tile's rejected list, which only filters later
 * proposals (decision D4). Stored through the API (D5); the API keeps the items as opaque JSON.
 *
 * @param baseVersion    the tile Version the proposal was computed against (informational: every item
 *                       is checked against the current graph when it is accepted)
 * @param builderVersion {@link TileBuilder#BUILDER_VERSION} of the build
 * @param createdBy      who asked for the build, or {@code null}
 * @param cellCount      the build's road cells
 * @param levelCount     the build's levels
 * @param warnings       the build's warnings (API strings)
 * @param items          pending items, numbered from 1 (numbers stay fixed while the proposal is reviewed)
 * @param rejected       rejected items of this and earlier proposals
 */
public record TileProposal(int baseVersion, int builderVersion, String createdBy, int cellCount, int levelCount,
                           List<String> warnings, List<Item> items, List<Item> rejected) {

    public TileProposal {
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        rejected = List.copyOf(Objects.requireNonNull(rejected, "rejected"));
    }

    /** What an item proposes. */
    public enum Kind {
        /** A road the build found that the stored graph lacks (with its new end nodes). */
        EDGE_ADDED("added"),
        /** A stored detected edge the build no longer finds. */
        EDGE_REMOVED("removed"),
        /** A stored detected edge the build traces elsewhere, or through other gate doors, WorldGuard regions or a profile. */
        EDGE_CHANGED("changed"),
        /** A stored, unlocked node the build places more than {@link TileDiff#MOVE_TOLERANCE} blocks away. */
        NODE_MOVED("moved"),
        /** A stored, unlocked detected node the build no longer finds. */
        NODE_REMOVED("removed");

        private final String word;

        Kind(String word) {
            this.word = word;
        }

        /** The selector word of {@code accept|reject <word>}: added, removed, changed, moved. */
        public String word() {
            return word;
        }

        public boolean isEdge() {
            return this == EDGE_ADDED || this == EDGE_REMOVED || this == EDGE_CHANGED;
        }
    }

    /**
     * One end of an edge item, or the node of a node item.
     *
     * @param nodeId the stored node id, or {@link #NEW} for a node the build found that the tile lacks
     * @param x      floor block x (the build's position; a stored end keeps its stored position unless a
     *               {@link Kind#NODE_MOVED} item is accepted)
     * @param y      floor block y
     * @param z      floor block z
     * @param kind   the node's kind in the build (or its stored kind when the build lost it)
     */
    public record End(int nodeId, int x, int y, int z, RoadNodeKind kind) {
        /** {@link #nodeId} of a node the stored graph lacks. */
        public static final int NEW = 0;

        public End {
            Objects.requireNonNull(kind, "kind");
            if (nodeId < 0) {
                throw new IllegalArgumentException("nodeId must be >= 0 (0 = new)");
            }
        }

        public boolean isNew() {
            return nodeId == NEW;
        }

        public int[] position() {
            return new int[] {x, y, z};
        }
    }

    /**
     * One proposed change. Which fields are set depends on {@link #kind}:
     * <ul>
     *   <li>edge items: {@code from}, {@code to}, {@code geometry} (the proposed polyline; for a removed
     *       edge the stored one) and, for added/changed edges, the proposed attributes;
     *       {@code edgeId} for removed and changed edges, {@code before} (the stored polyline) for
     *       changed ones;</li>
     *   <li>node items: {@code node} (the stored node), and for a moved node {@code target}.</li>
     * </ul>
     *
     * @param n           the number shown to the admin (1-based)
     * @param kind        what the item proposes
     * @param edgeId      the stored edge, or 0
     * @param from        edge From end, or {@code null}
     * @param to          edge To end, or {@code null}
     * @param geometry    proposed (or, for a removed edge, stored) polyline From → To; empty for node items
     * @param before      a changed edge's stored polyline (From → To); empty otherwise
     * @param length      proposed walked length
     * @param avgWidth    proposed average width
     * @param profileId   proposed profile
     * @param gateDoorIds proposed gate doors
     * @param domainIds   proposed domains
     * @param regionIds   proposed WorldGuard regions
     * @param node        the stored node of a node item, or {@code null}
     * @param target      a moved node's new position {@code {x, y, z}}, or {@code null}
     * @param note        a short human description of what changed (changed edges), or {@code ""}
     * @param lockedNodeIds on the rejected list only: the nodes rejecting this removal locked (they were unlocked
     *                    before), so {@code unreject} can unlock exactly those again
     */
    public record Item(int n, Kind kind, int edgeId, End from, End to, List<int[]> geometry, List<int[]> before,
                       double length, double avgWidth, OptionalInt profileId, List<Integer> gateDoorIds,
                       List<Integer> domainIds, List<String> regionIds, End node, int[] target, String note,
                       List<Integer> lockedNodeIds) {

        /** An item that locked nothing. */
        public Item(int n, Kind kind, int edgeId, End from, End to, List<int[]> geometry, List<int[]> before,
                    double length, double avgWidth, OptionalInt profileId, List<Integer> gateDoorIds,
                    List<Integer> domainIds, List<String> regionIds, End node, int[] target, String note) {
            this(n, kind, edgeId, from, to, geometry, before, length, avgWidth, profileId, gateDoorIds, domainIds, regionIds,
                node, target, note, List.of());
        }

        public Item {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(profileId, "profileId");
            geometry = copy(geometry);
            before = copy(before);
            gateDoorIds = List.copyOf(Objects.requireNonNull(gateDoorIds, "gateDoorIds"));
            domainIds = List.copyOf(Objects.requireNonNull(domainIds, "domainIds"));
            regionIds = List.copyOf(Objects.requireNonNull(regionIds, "regionIds"));
            note = note == null ? "" : note;
            target = target == null ? null : target.clone();
            lockedNodeIds = lockedNodeIds == null ? List.of() : List.copyOf(lockedNodeIds);
            if (kind.isEdge()) {
                Objects.requireNonNull(from, "from");
                Objects.requireNonNull(to, "to");
                if (geometry.size() < 2) {
                    throw new IllegalArgumentException("an edge item needs at least two geometry points");
                }
                if (kind != Kind.EDGE_ADDED && edgeId <= 0) {
                    throw new IllegalArgumentException(kind + " needs the stored edge id");
                }
            } else {
                Objects.requireNonNull(node, "node");
                if (node.isNew()) {
                    throw new IllegalArgumentException(kind + " needs a stored node");
                }
                if (kind == Kind.NODE_MOVED && (target == null || target.length != 3)) {
                    throw new IllegalArgumentException("a moved node needs its target {x, y, z}");
                }
            }
        }

        /** The same item with another number. */
        public Item numbered(int number) {
            return new Item(number, kind, edgeId, from, to, geometry, before, length, avgWidth, profileId, gateDoorIds,
                domainIds, regionIds, node, target, note, lockedNodeIds);
        }

        /** The same item recording the nodes its rejection locked. */
        public Item withLockedNodes(List<Integer> nodeIds) {
            return new Item(n, kind, edgeId, from, to, geometry, before, length, avgWidth, profileId, gateDoorIds,
                domainIds, regionIds, node, target, note, nodeIds);
        }

        /** Whether rejecting this item keeps a stored road part (confirms the edge, locks the node) instead of listing a change. */
        public boolean isRemoval() {
            return kind == Kind.EDGE_REMOVED || kind == Kind.NODE_REMOVED;
        }

        /** Where the admin is teleported to look at the item: the middle of the polyline (by length), or the node. */
        public int[] focus() {
            if (!kind.isEdge()) {
                return kind == Kind.NODE_MOVED ? target.clone() : node.position();
            }
            double total = 0;
            for (int i = 1; i < geometry.size(); i++) {
                total += distance(geometry.get(i - 1), geometry.get(i));
            }
            double half = total / 2;
            for (int i = 1; i < geometry.size(); i++) {
                int[] a = geometry.get(i - 1);
                int[] b = geometry.get(i);
                double step = distance(a, b);
                if (step >= half && step > 0) {
                    double t = half / step;
                    return new int[] {(int) Math.round(a[0] + t * (b[0] - a[0])), (int) Math.round(a[1] + t * (b[1] - a[1])),
                        (int) Math.round(a[2] + t * (b[2] - a[2]))};
                }
                half -= step;
            }
            return geometry.get(geometry.size() / 2).clone();
        }

        /**
         * One line for chat: "item 3: added edge 41 m (#3588 → new junction)". The item number is what {@code accept} and
         * {@code reject} take; a {@code #} number is a stored edge or node id.
         */
        public String describe() {
            return "item " + n + ": " + what();
        }

        /** What the item proposes, without its number: "added edge 41 m (#3588 → new junction)". */
        public String what() {
            return switch (kind) {
                case EDGE_ADDED -> "added edge " + Math.round(length) + " m (" + label(from) + " → " + label(to) + ")";
                case EDGE_REMOVED -> "removed edge #" + edgeId + " (" + label(from) + " → " + label(to) + ")";
                case EDGE_CHANGED -> "changed edge #" + edgeId + (note.isEmpty() ? "" : ": " + note);
                case NODE_MOVED -> "moved node #" + node.nodeId() + " by " + Math.round(distance(node.position(), target)) + " blocks";
                case NODE_REMOVED -> "removed " + node.kind().apiName().toLowerCase() + " #" + node.nodeId();
            };
        }

        private static String label(End end) {
            return end.isNew() ? "new " + end.kind().apiName().toLowerCase() : "#" + end.nodeId();
        }

        private static List<int[]> copy(List<int[]> points) {
            List<int[]> out = new ArrayList<>();
            if (points != null) {
                for (int[] p : points) {
                    if (p == null || p.length != 3) {
                        throw new IllegalArgumentException("geometry points must be {x, y, z}");
                    }
                    out.add(p.clone());
                }
            }
            return List.copyOf(out);
        }
    }

    /** The pending item with this number. */
    public java.util.Optional<Item> item(int n) {
        return items.stream().filter(i -> i.n() == n).findFirst();
    }

    /** Pending items of one kind. */
    public long count(Kind kind) {
        return items.stream().filter(i -> i.kind() == kind).count();
    }

    /** Whether nothing is pending (the rejected list may still hold entries). */
    public boolean isEmpty() {
        return items.isEmpty();
    }

    /** The same proposal with other pending items and rejected list. */
    public TileProposal with(List<Item> items, List<Item> rejected) {
        return new TileProposal(baseVersion, builderVersion, createdBy, cellCount, levelCount, warnings, items, rejected);
    }

    static double distance(int[] a, int[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
