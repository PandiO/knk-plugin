package net.knightsandkings.knk.core.roads.walk;

import java.util.Objects;
import java.util.Set;

import net.knightsandkings.knk.core.navigation.shape.NavWorld;

/**
 * The shaping module's {@link NavWorld} over a captured walk terrain (off-road navigation phase 6.1,
 * PandiO/knk-workspace#710, DESIGN § 3.4, § 11, decision O11): lets {@code CorridorShaper} shape a walk path off the
 * main thread from the same {@link WalkTerrain} the walk search read, never the live world.
 *
 * <p>A <em>standable floor</em> is a standing walk cell as the search sees it (a walk floor with the mover's headroom,
 * doors and climbables passable, wading only where the profile wades, never with the head in water) that the mover's
 * {@link CellAccess} lets it enter. Everything is "loaded": a block outside the capture is not solid, so it is no floor
 * and the corridor ends there. Regions are not told (the access already decided where the mover may stand), so every
 * cell is in the line's region set. One instance per shaping (it memoises the terrain and is not thread-safe); the
 * world name is ignored.
 */
public final class WalkTerrainNavWorld implements NavWorld {

    private final MemoSurface surface;
    private final WalkCells cells;
    private final WalkGrid grid;
    private final MovementProfile profile;
    private final CellAccess access;

    /**
     * @param terrain the captured terrain of the walk request (the one the search read)
     * @param profile the mover (headroom, wading, climbables)
     * @param access  where the mover may stand ({@link CellAccess#OPEN} for anywhere)
     */
    public WalkTerrainNavWorld(WalkTerrain terrain, MovementProfile profile, CellAccess access) {
        Objects.requireNonNull(terrain, "terrain");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.access = Objects.requireNonNull(access, "access");
        this.surface = new MemoSurface(terrain.surface(), terrain.cells());
        this.cells = surface.cells();
        this.grid = WalkSearch.gridFor(surface, cells, terrain, profile);
    }

    /** Whether the floor block is a standing walk cell the mover may enter: the rule of {@link WalkClearance}. */
    public boolean isStanding(int x, int floorY, int z) {
        if (!grid.isCell(x, floorY, z)) {
            return false;
        }
        if (cells.isWater(x, floorY + 1, z) && !profile.wades()) {
            return false;
        }
        for (int h = 2; h <= profile.headroom(); h++) {
            if (cells.isWater(x, floorY + h, z)) {
                return false;
            }
        }
        return access.allows(x, floorY + 1, z);
    }

    @Override
    public boolean isLoaded(String world, int x, int z) {
        return true;
    }

    @Override
    public boolean isStandableFloor(String world, int x, int floorY, int z) {
        return isStanding(x, floorY, z);
    }

    @Override
    public Set<String> regionsAt(String world, int x, int feetY, int z) {
        return Set.of();
    }

    /** The mover's headroom, plus one block when the block above it is free too (the shaper needs 1.8). */
    @Override
    public double freeHeight(String world, int x, int floorY, int z) {
        if (!isStanding(x, floorY, z)) {
            return 0.0;
        }
        int top = floorY + profile.headroom() + 1;
        return top < surface.maxY() && grid.isPassable(x, top, z) ? profile.headroom() + 1.0 : profile.headroom();
    }

    @Override
    public boolean blocksBody(String world, int x, int y, int z) {
        return surface.isSolid(x, y, z) && !grid.isPassable(x, y, z) && !surface.isStairOrSlab(x, y, z);
    }

    @Override
    public double surfaceY(String world, double x, int floorY, double z) {
        return surface.isStairOrSlab((int) Math.floor(x), floorY, (int) Math.floor(z)) ? floorY + 0.5 : floorY + 1;
    }
}
