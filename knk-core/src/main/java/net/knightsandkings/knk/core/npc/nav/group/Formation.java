package net.knightsandkings.knk.core.npc.nav.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Where the followers of a group walk relative to their leader (decision Q4; BEHAVIOR_MODEL NAV-05). A slot is
 * measured along the group's path: {@code back} blocks behind the leader (negative: ahead of it) and {@code side}
 * blocks to the right of the path's heading there (negative: left). No engine types; immutable.
 *
 * <p>Every formation has a list of widths, widest first ({@link #widths}; implementation plan 4b.3 item 3): where the
 * path is too narrow for one the group steps down to the next, and it folds into the column ({@link #columnSlots})
 * only where 2 abreast does not fit. Rows are {@code spacing} apart, neighbours in a row {@code lateralSpacing}.
 */
public final class Formation {

    public enum Type {
        /** Single file behind the leader (the default). */
        COLUMN,
        /** Rows of {@code ranksWidth} behind the leader: 2 gives pairs. */
        RANKS,
        /** A loose crowd behind the leader: rows of three (then two), each slot nudged up to a quarter spacing off its place. */
        CLUSTER,
        /** Bodyguards around the leader in the centre: an octagon, then wider rings of sixteen, twenty-four, ... */
        ESCORT_RING;

        /** The config name: lower case with dashes ({@code escort-ring}). */
        public String configName() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        /** The type of that config name (any case, dashes or underscores), or null. */
        public static Type byName(String name) {
            if (name == null) {
                return null;
            }
            String wanted = name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            for (Type type : values()) {
                if (type.configName().equals(wanted)) {
                    return type;
                }
            }
            return null;
        }
    }

    /** @param back blocks behind the leader along the path (negative: ahead); @param side blocks to the right */
    public record Slot(double back, double side) {
    }

    /**
     * One width a formation can walk in (implementation plan 4b.3 item 3): {@code abreast} NPCs side by side, or the
     * escort ring ({@code ring}, as wide as its rings). A formation's widths are listed widest first and end with
     * the column ({@code abreast} 1).
     */
    public record Width(int abreast, boolean ring) {
        /** For logs: "3 abreast", "a column", "the escort ring". */
        public String describe() {
            return ring ? "the escort ring" : abreast == 1 ? "a column" : abreast + " abreast";
        }

        public boolean column() {
            return !ring && abreast == 1;
        }
    }

    /** Slots on the first escort ring; ring {@code r} (from 1) has {@code r} times as many. */
    static final int RING_SLOTS = 8;
    /** The first escort ring's radius in lateral spacings (developer decision 2026-11-15, plan 4b.4 question 12). */
    static final double RING_RADIUS = 1.25;
    /** The cluster's nudge: at most this share of a spacing off a slot's place (plan 4b.3 item 3.2). */
    static final double NUDGE = 0.25;

    private static final Width COLUMN_WIDTH = new Width(1, false);
    private static final Width RING_WIDTH = new Width(0, true);

    private final Type type;
    private final double spacing;
    private final double lateralSpacing;
    private final int ranksWidth;

    /**
     * @param spacing        blocks between the rows of the formation and between NPCs in its column
     *                       ({@code group-spacing})
     * @param lateralSpacing blocks between neighbours in a row ({@code group-lateral-spacing}; NAV-22)
     * @param ranksWidth     NPCs per row for {@link Type#RANKS}
     */
    public Formation(Type type, double spacing, double lateralSpacing, int ranksWidth) {
        this.type = Objects.requireNonNull(type, "type");
        if (!(spacing > 0) || !(lateralSpacing > 0)) {
            throw new IllegalArgumentException("spacing must be positive");
        }
        if (ranksWidth < 1) {
            throw new IllegalArgumentException("ranksWidth must be at least 1");
        }
        this.spacing = spacing;
        this.lateralSpacing = lateralSpacing;
        this.ranksWidth = ranksWidth;
    }

    /** One spacing for rows and neighbours alike (the Phase 4 formations). */
    public Formation(Type type, double spacing, int ranksWidth) {
        this(type, spacing, spacing, ranksWidth);
    }

    public static Formation column(double spacing) {
        return new Formation(Type.COLUMN, spacing, 1);
    }

    public Type type() {
        return type;
    }

    public double spacing() {
        return spacing;
    }

    public double lateralSpacing() {
        return lateralSpacing;
    }

    public int ranksWidth() {
        return ranksWidth;
    }

    /** The same formation with other spacings and ranks width. */
    public Formation with(Type other) {
        return new Formation(other, spacing, lateralSpacing, ranksWidth);
    }

    /**
     * The widths this formation walks in, widest first, the column last (implementation plan 4b.3 item 3): ranks
     * from {@code group-ranks-width} down to 2, a cluster 3 and 2, the escort ring its rings, then rows of 3 and 2.
     */
    public List<Width> widths() {
        List<Width> out = new ArrayList<>(ranksWidth + 2);
        switch (type) {
            case COLUMN -> { }
            case RANKS -> {
                for (int k = ranksWidth; k >= 2; k--) {
                    out.add(new Width(k, false));
                }
            }
            case CLUSTER -> {
                out.add(new Width(3, false));
                out.add(new Width(2, false));
            }
            case ESCORT_RING -> {
                out.add(RING_WIDTH);
                out.add(new Width(3, false));
                out.add(new Width(2, false));
            }
        }
        out.add(COLUMN_WIDTH);
        return List.copyOf(out);
    }

    /** The index of the column in {@link #widths()}. */
    public int columnLevel() {
        return widths().size() - 1;
    }

    /** The slots of {@code followers} followers in the formation's widest width, the nearest to the leader first. */
    public List<Slot> slots(int followers) {
        return slots(followers, 0);
    }

    /** The slots of {@code followers} followers in width {@code level} of {@link #widths()}. */
    public List<Slot> slots(int followers, int level) {
        List<Width> widths = widths();
        Width width = widths.get(Math.max(0, Math.min(level, widths.size() - 1)));
        if (width.column() && type != Type.ESCORT_RING) {
            return columnSlots(followers);
        }
        return switch (type) {
            case COLUMN -> columnSlots(followers);
            case RANKS -> rows(followers, width.abreast(), false);
            case CLUSTER -> rows(followers, width.abreast(), true);
            case ESCORT_RING -> width.ring() ? rings(followers) : escortRows(followers, width.abreast());
        };
    }

    /** Single file behind the leader: what every formation folds into where the path is narrow. */
    public List<Slot> columnSlots(int followers) {
        List<Slot> out = new ArrayList<>(followers);
        for (int i = 0; i < followers; i++) {
            out.add(new Slot((i + 1) * spacing, 0));
        }
        return out;
    }

    private List<Slot> rows(int followers, int width, boolean loose) {
        List<Slot> out = new ArrayList<>(followers);
        for (int i = 0; i < followers; i++) {
            int row = i / width + 1;
            int col = i % width;
            double back = row * spacing;
            double side = (col - (width - 1) / 2.0) * lateralSpacing;
            if (loose) {
                // the same nudge for the same slot every time: a crowd, not a grid, and no slot jumps about; the
                // outer lanes are nudged inwards only, so the nudge never makes a width wider than it fits
                back += (unit(i * 2) - 0.5) * 2 * NUDGE * spacing;
                double nudge = unit(i * 2 + 1);
                side += side == 0 ? (nudge - 0.5) * 2 * NUDGE * lateralSpacing
                    : -Math.signum(side) * nudge * NUDGE * lateralSpacing;
            }
            out.add(new Slot(back, side));
        }
        return out;
    }

    /**
     * The narrowed escort ring (developer decision 2026-11-15, plan 4b.4 question 3): 3 abreast with the leader in
     * the middle of the second row (a row of three ahead of it, one escort either side, rows of three behind); 2
     * abreast as a pair ahead, the leader, pairs behind; a column with one escort ahead and the rest behind.
     */
    private List<Slot> escortRows(int followers, int width) {
        List<Slot> out = new ArrayList<>(followers);
        double[] sides = width == 3 ? new double[] {0, -lateralSpacing, lateralSpacing}
            : width == 2 ? new double[] {-lateralSpacing / 2, lateralSpacing / 2} : new double[] {0};
        for (int k = 0; k < sides.length && out.size() < followers; k++) {
            out.add(new Slot(-spacing, sides[k])); // the row ahead of the leader
        }
        if (width == 3) {
            for (double side : new double[] {lateralSpacing, -lateralSpacing}) {
                if (out.size() < followers) {
                    out.add(new Slot(0, side)); // either side of the leader
                }
            }
        }
        for (int row = 1; out.size() < followers; row++) {
            for (int k = 0; k < sides.length && out.size() < followers; k++) {
                out.add(new Slot(row * spacing, sides[k]));
            }
        }
        return out;
    }

    private List<Slot> rings(int followers) {
        List<Slot> out = new ArrayList<>(followers);
        int ring = 1;
        int placed = 0;
        while (placed < followers) {
            int onRing = RING_SLOTS * ring;
            double radius = RING_RADIUS * lateralSpacing * ring;
            for (int k = 0; k < onRing && placed < followers; k++, placed++) {
                // the first slots straight ahead and behind, then alternating sides, so a small escort is balanced
                double angle = 2 * Math.PI * ringOrder(k, onRing) / onRing;
                out.add(new Slot(-radius * Math.cos(angle), radius * Math.sin(angle)));
            }
            ring++;
        }
        return out;
    }

    /** Spreads the first few slots of a ring evenly: 0, half way round, the quarters, then the rest in order. */
    private static int ringOrder(int k, int onRing) {
        int[] first = {0, onRing / 2, onRing / 4, 3 * onRing / 4};
        if (k < first.length) {
            return first[k];
        }
        int skipped = 0;
        for (int position = 0; position < onRing; position++) {
            boolean taken = false;
            for (int f : first) {
                taken |= f == position;
            }
            if (!taken && skipped++ == k - first.length) {
                return position;
            }
        }
        return k;
    }

    /** A fixed number in [0, 1) per index. */
    private static double unit(int i) {
        long h = i * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h >>> 11) / (double) (1L << 53);
    }

    /** The widest slot's distance from the path's centre, in the widest width. */
    public double halfWidth(int followers) {
        return halfWidth(followers, 0);
    }

    /** The widest slot's distance from the path's centre in width {@code level}. */
    public double halfWidth(int followers, int level) {
        double max = 0;
        for (Slot slot : slots(followers, level)) {
            max = Math.max(max, Math.abs(slot.side()));
        }
        return max;
    }

    /** How far the rearmost slot is behind the leader (0 when every slot is ahead), in the widest width. */
    public double depth(int followers) {
        return depth(followers, 0);
    }

    public double depth(int followers, int level) {
        double max = 0;
        for (Slot slot : slots(followers, level)) {
            max = Math.max(max, slot.back());
        }
        return max;
    }

    /** How far the foremost slot is ahead of the leader (0 when every slot is behind), in the widest width. */
    public double front(int followers) {
        return front(followers, 0);
    }

    public double front(int followers, int level) {
        double max = 0;
        for (Slot slot : slots(followers, level)) {
            max = Math.max(max, -slot.back());
        }
        return max;
    }

    @Override
    public String toString() {
        return type.configName() + (type == Type.RANKS ? " " + ranksWidth + " wide" : "") + ", spacing " + spacing
            + (lateralSpacing != spacing ? ", lateral spacing " + lateralSpacing : "");
    }
}
