package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.util.BlockKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A walk path (KNG-51 {@code LAST_MILE_PATHFINDING.md} §3): the cells from the start cell to the
 * first arrived cell, in order. Each cell is either a <b>standing</b> cell (its key is the floor
 * block) or a <b>ladder</b> cell (its key is the climbable block the feet are in). {@link #points()}
 * gives every cell as a floor position — block centre x/z and the floor y (a ladder cell's floor y
 * is its feet y − 1) — which is the navigation network's convention, so a trail can draw it like a
 * route ({@code TrailRenderer.drawPath}, Phase C) with no further world reads.
 */
public final class WalkPath {

    private final long[] cells;
    private final boolean[] ladder;
    private final double length;
    private final double cost;

    WalkPath(long[] cells, boolean[] ladder, double length, double cost) {
        if (cells.length == 0 || cells.length != ladder.length) {
            throw new IllegalArgumentException("a path needs at least one cell and a kind per cell");
        }
        this.cells = cells.clone();
        this.ladder = ladder.clone();
        this.length = length;
        this.cost = cost;
    }

    /** Number of cells (at least 1: the start cell). */
    public int size() {
        return cells.length;
    }

    /** The packed key of cell {@code i} (floor block, or the climbable block for a ladder cell). */
    public long cell(int i) {
        return cells[i];
    }

    /** Whether cell {@code i} is a ladder cell. */
    public boolean isLadder(int i) {
        return ladder[i];
    }

    /** Floor y of cell {@code i} (a ladder cell: its feet y − 1). */
    public int floorY(int i) {
        int y = BlockKey.y(cells[i]);
        return ladder[i] ? y - 1 : y;
    }

    /** Blocks walked: horizontal moves (1 or √2) plus every block climbed or dropped. */
    public double length() {
        return length;
    }

    /** The search cost of the path (length plus penalties, water factor, access costs). */
    public double cost() {
        return cost;
    }

    /** Every cell as {@code {x + 0.5, floorY, z + 0.5}}, start first. A fresh list each call. */
    public List<double[]> points() {
        List<double[]> points = new ArrayList<>(cells.length);
        for (int i = 0; i < cells.length; i++) {
            points.add(new double[] {BlockKey.x(cells[i]) + 0.5, floorY(i), BlockKey.z(cells[i]) + 0.5});
        }
        return Collections.unmodifiableList(points);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("WalkPath[");
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(ladder[i] ? "L" : "").append('(').append(BlockKey.x(cells[i])).append(',')
                .append(BlockKey.y(cells[i])).append(',').append(BlockKey.z(cells[i])).append(')');
        }
        return sb.append(", length=").append(String.format("%.2f", length)).append(']').toString();
    }
}
