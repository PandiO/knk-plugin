package net.knightsandkings.knk.core.npc.nav.crowd;

import java.util.HashMap;
import java.util.Map;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;

/**
 * Who stands where (implementation plan 4c.3 decision 3.1, link M4; BEHAVIOR_MODEL NAV-26): the feet cell of every
 * navigating NPC, rebuilt in one pass every {@link #UPDATE_TICKS} ticks (O(n)); a lookup is O(1). Between two rebuilds
 * the answers are up to {@link #UPDATE_TICKS} ticks old, which at walking speed is about a block. Engine-neutral, main
 * thread.
 *
 * <p>It also keeps the counts link M3 asked for (its note on lane merges with collision on): the cells two or more NPCs
 * shared at the last rebuild, the most NPCs in one cell, and how many rebuilds had a shared cell.
 */
public final class Occupancy {

    /** Ticks between two rebuilds (decision 3.1). */
    public static final int UPDATE_TICKS = 5;

    /** Per world: packed feet cell to the ids standing in it (a small array, usually one). */
    private final Map<String, Map<Long, int[]>> cells = new HashMap<>();
    private final Map<Integer, NavPoint> positions = new HashMap<>();
    private Map<String, Map<Long, int[]>> building;
    private Map<Integer, NavPoint> buildingPositions;
    private int sharedCells;
    private int mostInOneCell;
    private long rebuilds;
    private long rebuildsWithSharing;
    private int mostSharedCells;
    private long nanos;
    private double closest = Double.NaN;
    private long rebuildsWithOverlap;

    /** Starts a rebuild; {@link #add} each NPC, then {@link #finish}. */
    public void begin() {
        building = new HashMap<>();
        buildingPositions = new HashMap<>();
    }

    /** One NPC's feet position in the rebuild under way. */
    public void add(int id, NavPoint feet) {
        if (building == null) {
            throw new IllegalStateException("begin() first");
        }
        if (feet == null) {
            return;
        }
        Map<Long, int[]> world = building.computeIfAbsent(feet.world(), w -> new HashMap<>());
        long key = pack(feet.blockX(), feet.blockY(), feet.blockZ());
        int[] ids = world.get(key);
        if (ids == null) {
            world.put(key, new int[] {id});
        } else {
            int[] more = java.util.Arrays.copyOf(ids, ids.length + 1);
            more[ids.length] = id;
            world.put(key, more);
        }
        buildingPositions.put(id, feet);
    }

    /** Ends the rebuild: the new cells replace the old ones; {@code nanos} is the rebuild's cost, for the stats. */
    public void finish(long nanos) {
        if (building == null) {
            throw new IllegalStateException("begin() first");
        }
        cells.clear();
        cells.putAll(building);
        positions.clear();
        positions.putAll(buildingPositions);
        building = null;
        buildingPositions = null;
        int shared = 0;
        int most = 0;
        boolean overlap = false;
        for (Map<Long, int[]> world : cells.values()) {
            for (int[] ids : world.values()) {
                most = Math.max(most, ids.length);
                if (ids.length > 1) {
                    shared++;
                    // two bodies in one cell may still stand side by side (0.6 apart fits a cell): their distance tells
                    for (int i = 0; i < ids.length; i++) {
                        for (int j = i + 1; j < ids.length; j++) {
                            double d = positions.get(ids[i]).horizontalDistance(positions.get(ids[j]));
                            closest = Double.isNaN(closest) ? d : Math.min(closest, d);
                            overlap |= d < BODY_WIDTH - 1e-9;
                        }
                    }
                }
            }
        }
        if (overlap) {
            rebuildsWithOverlap++;
        }
        sharedCells = shared;
        mostInOneCell = Math.max(mostInOneCell, most);
        mostSharedCells = Math.max(mostSharedCells, shared);
        rebuilds++;
        if (shared > 0) {
            rebuildsWithSharing++;
        }
        this.nanos += Math.max(0, nanos);
    }

    /** The NPCs in a feet cell (empty when none). */
    public int[] at(String world, int x, int y, int z) {
        Map<Long, int[]> w = cells.get(world);
        int[] ids = w == null ? null : w.get(pack(x, y, z));
        return ids == null ? new int[0] : ids;
    }

    /** Whether an NPC other than {@code except} stands in a feet cell. */
    public boolean occupied(String world, int x, int y, int z, int except) {
        return occupant(world, x, y, z, except) >= 0;
    }

    /** An NPC other than {@code except} standing in a feet cell, or -1. */
    public int occupant(String world, int x, int y, int z, int except) {
        Map<Long, int[]> w = cells.get(world);
        int[] ids = w == null ? null : w.get(pack(x, y, z));
        if (ids == null) {
            return -1;
        }
        for (int id : ids) {
            if (id != except) {
                return id;
            }
        }
        return -1;
    }

    /** Whether an NPC other than {@code except} stands in the feet cell of {@code feet}. */
    public boolean occupied(NavPoint feet, int except) {
        return feet != null && occupied(feet.world(), feet.blockX(), feet.blockY(), feet.blockZ(), except);
    }

    /** An NPC's feet position at the last rebuild, or null. */
    public NavPoint positionOf(int id) {
        return positions.get(id);
    }

    /** NPCs in the last rebuild. */
    public int size() {
        return positions.size();
    }

    /** Cells two or more NPCs shared at the last rebuild. */
    public int sharedCells() {
        return sharedCells;
    }

    /** The most NPCs seen in one cell at any rebuild since the counts were reset. */
    public int mostInOneCell() {
        return mostInOneCell;
    }

    /** The most shared cells seen at one rebuild since the counts were reset. */
    public int mostSharedCells() {
        return mostSharedCells;
    }

    /** A body's width: two NPCs closer than this overlap (link M3's note on lane merges with collision on). */
    public static final double BODY_WIDTH = 0.6;

    /** The closest two NPCs in one cell came at any rebuild since the counts were reset, NaN when none shared one. */
    public double closest() {
        return closest;
    }

    /** Rebuilds where two NPCs in one cell stood closer than {@link #BODY_WIDTH} (bodies overlapping). */
    public long rebuildsWithOverlap() {
        return rebuildsWithOverlap;
    }

    /** Rebuilds since the counts were reset. */
    public long rebuilds() {
        return rebuilds;
    }

    /** Rebuilds with at least one shared cell since the counts were reset. */
    public long rebuildsWithSharing() {
        return rebuildsWithSharing;
    }

    /** The rebuilds' main-thread time since the counts were reset. */
    public long nanos() {
        return nanos;
    }

    /** Resets the counts (not the cells). */
    public void resetCounts() {
        mostInOneCell = 0;
        mostSharedCells = 0;
        rebuilds = 0;
        rebuildsWithSharing = 0;
        nanos = 0;
        closest = Double.NaN;
        rebuildsWithOverlap = 0;
    }

    /** A feet cell as one long: x and z 26 bits each, y 12 bits (all two's complement, wrapped). */
    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
