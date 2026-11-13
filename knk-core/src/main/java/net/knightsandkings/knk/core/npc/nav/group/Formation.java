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
 * <p>Every formation has a column form ({@link #columnSlots}): where the path is too narrow for the formation's
 * width the group folds into it and reforms after.
 */
public final class Formation {

    public enum Type {
        /** Single file behind the leader (the default). */
        COLUMN,
        /** Rows of {@code ranksWidth} behind the leader: 2 gives pairs. */
        RANKS,
        /** A loose crowd behind the leader: rows of three, each slot nudged up to a quarter spacing off its place. */
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

    /** Slots on the first escort ring; ring {@code r} (from 1) has {@code r} times as many. */
    static final int RING_SLOTS = 8;
    /** The first escort ring's radius in spacings. */
    static final double RING_RADIUS = 1.25;

    private final Type type;
    private final double spacing;
    private final int ranksWidth;

    /**
     * @param spacing    blocks between neighbouring slots
     * @param ranksWidth NPCs per row for {@link Type#RANKS}
     */
    public Formation(Type type, double spacing, int ranksWidth) {
        this.type = Objects.requireNonNull(type, "type");
        if (!(spacing > 0)) {
            throw new IllegalArgumentException("spacing must be positive");
        }
        if (ranksWidth < 1) {
            throw new IllegalArgumentException("ranksWidth must be at least 1");
        }
        this.spacing = spacing;
        this.ranksWidth = ranksWidth;
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

    public int ranksWidth() {
        return ranksWidth;
    }

    /** The slots of {@code followers} followers, the nearest to the leader first. */
    public List<Slot> slots(int followers) {
        return switch (type) {
            case COLUMN -> columnSlots(followers);
            case RANKS -> rows(followers, ranksWidth, spacing, spacing, false);
            case CLUSTER -> rows(followers, 3, spacing, spacing, true);
            case ESCORT_RING -> rings(followers);
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

    private static List<Slot> rows(int followers, int width, double rowSpacing, double sideSpacing, boolean loose) {
        List<Slot> out = new ArrayList<>(followers);
        for (int i = 0; i < followers; i++) {
            int row = i / width + 1;
            int col = i % width;
            double back = row * rowSpacing;
            double side = (col - (width - 1) / 2.0) * sideSpacing;
            if (loose) {
                // the same nudge for the same slot every time: a crowd, not a grid, and no slot jumps about
                back += (unit(i * 2) - 0.5) * 0.5 * rowSpacing;
                side += (unit(i * 2 + 1) - 0.5) * 0.5 * sideSpacing;
            }
            out.add(new Slot(back, side));
        }
        return out;
    }

    private List<Slot> rings(int followers) {
        List<Slot> out = new ArrayList<>(followers);
        int ring = 1;
        int placed = 0;
        while (placed < followers) {
            int onRing = RING_SLOTS * ring;
            double radius = RING_RADIUS * spacing * ring;
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

    /** The widest slot's distance from the path's centre. */
    public double halfWidth(int followers) {
        double max = 0;
        for (Slot slot : slots(followers)) {
            max = Math.max(max, Math.abs(slot.side()));
        }
        return max;
    }

    /** How far the rearmost slot is behind the leader (0 when every slot is ahead). */
    public double depth(int followers) {
        double max = 0;
        for (Slot slot : slots(followers)) {
            max = Math.max(max, slot.back());
        }
        return max;
    }

    /** How far the foremost slot is ahead of the leader (0 when every slot is behind). */
    public double front(int followers) {
        double max = 0;
        for (Slot slot : slots(followers)) {
            max = Math.max(max, -slot.back());
        }
        return max;
    }

    @Override
    public String toString() {
        return type.configName() + (type == Type.RANKS ? " " + ranksWidth + " wide" : "") + ", spacing " + spacing;
    }
}
