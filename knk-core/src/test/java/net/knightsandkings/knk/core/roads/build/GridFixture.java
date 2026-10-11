package net.knightsandkings.knk.core.roads.build;

import net.knightsandkings.knk.core.domain.roads.RoadMaterialRole;
import net.knightsandkings.knk.core.roads.build.ProfileSet.Profile;
import net.knightsandkings.knk.core.roads.survey.ProposedProfile.Material;
import net.knightsandkings.knk.core.util.BlockKey;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Builds a {@link SurfaceGrid} (and its {@link GateCells}) from ASCII layers for the builder tests
 * (plan Phase 2c "Test fixtures"). Every block not placed is air. One layer is drawn at one y: the
 * character at column {@code c}, row {@code r} places the block at {@code (x0 + c, y, z0 + r)}
 * (x grows to the right, z grows downwards):
 *
 * <pre>
 *   .  air (nothing)                    G  gravel floor            S  stone-brick floor
 *   a  polished andesite (edge)         c  cobblestone (ambiguous) /  stone-brick stairs
 *   _  stone-brick slab                 #  wall: stone from y to y+3 (blocks standing and headroom)
 *   X  one stone block (solid, not road) g  grass block (terrain)   L  lava      ~  water
 *   s  snow layer (overlay; the floor is the block below)   *  iron bars (a gate-door block; see gate())
 * </pre>
 *
 * Passability follows the real {@link PassabilityRules#defaults()} so the fixture and the paper
 * implementation agree on what a floor, an overlay and headroom are.
 */
public final class GridFixture implements SurfaceGrid, GateCells {

    public static final String AIR = "AIR";
    public static final String GRAVEL = "GRAVEL";
    public static final String STONE_BRICKS = "STONE_BRICKS";
    public static final String ANDESITE = "POLISHED_ANDESITE";
    public static final String COBBLESTONE = "COBBLESTONE";
    public static final String STAIRS = "STONE_BRICK_STAIRS";
    public static final String SLAB = "STONE_BRICK_SLAB";
    public static final String STONE = "STONE";
    public static final String GRASS = "GRASS_BLOCK";
    public static final String LAVA = "LAVA";
    public static final String WATER = "WATER";
    public static final String SNOW = "SNOW";
    public static final String IRON_BARS = "IRON_BARS";
    public static final String DIRT_PATH = "DIRT_PATH";

    /** How many blocks a {@code #} wall column occupies above its floor. */
    public static final int WALL_HEIGHT = 4;

    private final Map<Long, String> blocks = new HashMap<>();
    private final Map<Long, Integer> gateCells = new HashMap<>();
    private final PassabilityRules rules = PassabilityRules.defaults();
    private int minY = -64;
    private int maxY = 320;

    /** The town-road profile every golden test uses: stone bricks with andesite/cobblestone kerbs, width 5. */
    public static Profile townRoad() {
        return new Profile(1, "Town road", true, 3, 5, Set.of(), List.of(
            mat(STONE_BRICKS, RoadMaterialRole.SURFACE, false, 0.8),
            mat(ANDESITE, RoadMaterialRole.EDGE, false, 0.1),
            mat(COBBLESTONE, RoadMaterialRole.EDGE, true, 0.05),
            mat(STAIRS, RoadMaterialRole.ACCENT, false, 0.03),
            mat(SLAB, RoadMaterialRole.ACCENT, false, 0.02),
            mat(SNOW, RoadMaterialRole.OVERLAY, false, 0.0)));
    }

    /** A 1-3 wide gravel path. */
    public static Profile gravelPath() {
        return new Profile(2, "Gravel path", true, 1, 3, Set.of(), List.of(
            mat(GRAVEL, RoadMaterialRole.SURFACE, false, 1.0)));
    }

    /** Both default profiles, no town scope. */
    public static ProfileSet profiles() {
        return new ProfileSet(List.of(townRoad(), gravelPath()));
    }

    public static Material mat(String name, RoadMaterialRole role, boolean ambiguous, double centreShare) {
        return new Material(name, role, ambiguous, centreShare, 0.0, 100);
    }

    /** A grid with the world's default height range. */
    public GridFixture() {
    }

    public GridFixture heightRange(int minY, int maxYExclusive) {
        this.minY = minY;
        this.maxY = maxYExclusive;
        return this;
    }

    /** Draw a layer with its top-left corner at {@code (0, y, 0)}. */
    public GridFixture layer(int y, String... rows) {
        return layer(0, y, 0, rows);
    }

    /** Draw a layer with its top-left corner at {@code (x0, y, z0)}. */
    public GridFixture layer(int x0, int y, int z0, String... rows) {
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            for (int c = 0; c < row.length(); c++) {
                place(x0 + c, y, z0 + r, row.charAt(c));
            }
        }
        return this;
    }

    private void place(int x, int y, int z, char symbol) {
        switch (symbol) {
            case '.', ' ' -> { }
            case 'G' -> block(x, y, z, GRAVEL);
            case 'S' -> block(x, y, z, STONE_BRICKS);
            case 'a' -> block(x, y, z, ANDESITE);
            case 'c' -> block(x, y, z, COBBLESTONE);
            case '/' -> block(x, y, z, STAIRS);
            case '_' -> block(x, y, z, SLAB);
            case '#' -> column(x, z, y, y + WALL_HEIGHT - 1, STONE);
            case 'X' -> block(x, y, z, STONE);
            case 'g' -> block(x, y, z, GRASS);
            case 'L' -> block(x, y, z, LAVA);
            case '~' -> block(x, y, z, WATER);
            case 's' -> block(x, y, z, SNOW);
            case '*' -> block(x, y, z, IRON_BARS);
            default -> throw new IllegalArgumentException("unknown layer symbol '" + symbol + "'");
        }
    }

    /** Place one block. */
    public GridFixture block(int x, int y, int z, String material) {
        blocks.put(BlockKey.pack(x, y, z), material);
        return this;
    }

    /** Fill a column with one material from {@code yFrom} to {@code yTo} inclusive. */
    public GridFixture column(int x, int z, int yFrom, int yTo, String material) {
        for (int y = yFrom; y <= yTo; y++) {
            block(x, y, z, material);
        }
        return this;
    }

    /** Remove a block (air). */
    public GridFixture clear(int x, int y, int z) {
        blocks.remove(BlockKey.pack(x, y, z));
        return this;
    }

    /**
     * A closed gate door of {@code height} iron-bar blocks standing on the floor at {@code (x, floorY, z)}:
     * the blocks at {@code floorY + 1 … floorY + height} are placed and registered as door {@code doorId}.
     */
    public GridFixture gate(int doorId, int x, int floorY, int z, int height) {
        for (int h = 1; h <= height; h++) {
            block(x, floorY + h, z, IRON_BARS);
            gateCells.put(BlockKey.pack(x, floorY + h, z), doorId);
        }
        return this;
    }

    /** Register a block as part of a door's closed footprint without placing anything (an open gate). */
    public GridFixture gateCell(int doorId, int x, int y, int z) {
        gateCells.put(BlockKey.pack(x, y, z), doorId);
        return this;
    }

    /** The material at a block ({@code AIR} when nothing was placed). */
    public String material(int x, int y, int z) {
        return blocks.getOrDefault(BlockKey.pack(x, y, z), AIR);
    }

    /** Number of placed blocks. */
    public int blockCount() {
        return blocks.size();
    }

    // --- SurfaceGrid ---

    @Override
    public boolean isPassable(int x, int y, int z) {
        return rules.isPassable(material(x, y, z));
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return rules.isSolid(material(x, y, z));
    }

    @Override
    public boolean isHazard(int x, int y, int z) {
        return rules.isHazard(material(x, y, z));
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    @Override
    public String floorMaterial(int x, int y, int z) {
        String here = material(x, y, z);
        return rules.isOverlay(here) ? material(x, y - 1, z) : here;
    }

    @Override
    public boolean isStairOrSlab(int x, int y, int z) {
        return PassabilityRules.isStairOrSlab(material(x, y, z));
    }

    // --- GateCells ---

    @Override
    public OptionalInt doorAt(int x, int y, int z) {
        Integer door = gateCells.get(BlockKey.pack(x, y, z));
        return door == null ? OptionalInt.empty() : OptionalInt.of(door);
    }

    /** A span grid over this fixture with the default profiles. */
    public SpanGrid spanGrid() {
        return new SpanGrid(this, profiles(), this);
    }

    /** A span grid over this fixture with the given profiles. */
    public SpanGrid spanGrid(ProfileSet profiles) {
        return new SpanGrid(this, profiles, this);
    }
}
