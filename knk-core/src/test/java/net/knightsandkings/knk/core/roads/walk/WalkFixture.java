package net.knightsandkings.knk.core.roads.walk;

import net.knightsandkings.knk.core.roads.build.GridFixture;

import java.util.Set;

/**
 * ASCII fixtures for the walk search (KNG-51 {@code LAST_MILE_PATHFINDING.md} §12), on top of the
 * road builder's {@link GridFixture} (same layers, same {@code PassabilityRules.defaults()}), with
 * the walk-only blocks added. One layer is drawn at one y; column {@code c}, row {@code r} is
 * {@code (c, y, r)}:
 *
 * <pre>
 *   GridFixture's symbols (g grass, S stone bricks, / stairs, _ slab, # 4-high stone wall, X stone,
 *   L lava, ~ water, s snow layer …), plus:
 *   f  oak fence          w  cobblestone wall      p  glass pane
 *   D  oak door (this block and the one above)     I  iron door (this block and the one above)
 *   F  oak fence gate     H  ladder
 * </pre>
 */
final class WalkFixture {

    static final String OAK_FENCE = "OAK_FENCE";
    static final String COBBLESTONE_WALL = "COBBLESTONE_WALL";
    static final String GLASS_PANE = "GLASS_PANE";
    static final String OAK_DOOR = "OAK_DOOR";
    static final String IRON_DOOR = "IRON_DOOR";
    static final String OAK_FENCE_GATE = "OAK_FENCE_GATE";
    static final String LADDER = "LADDER";

    /**
     * The budget of the geometry fixtures: the design's expansion cap, but a length cap loose enough
     * that small detour fixtures test the walkability rules, not the 1.75× cap (that has its own tests).
     */
    static final WalkBudget GEOMETRY = new WalkBudget(20_000, 10.0, 200.0, 0.0, 2, 3);

    final GridFixture grid = new GridFixture();

    /** Draw a layer with its top-left corner at {@code (0, y, 0)}. */
    WalkFixture layer(int y, String... rows) {
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            for (int c = 0; c < row.length(); c++) {
                char symbol = row.charAt(c);
                switch (symbol) {
                    case 'f' -> grid.block(c, y, r, OAK_FENCE);
                    case 'w' -> grid.block(c, y, r, COBBLESTONE_WALL);
                    case 'p' -> grid.block(c, y, r, GLASS_PANE);
                    case 'D' -> grid.column(c, r, y, y + 1, OAK_DOOR);
                    case 'I' -> grid.column(c, r, y, y + 1, IRON_DOOR);
                    case 'F' -> grid.block(c, y, r, OAK_FENCE_GATE);
                    case 'H' -> grid.block(c, y, r, LADDER);
                    default -> grid.layer(c, y, r, String.valueOf(symbol));
                }
            }
        }
        return this;
    }

    /** A rectangle of one floor material at {@code y}, {@code x0..x1 × z0..z1} inclusive. */
    WalkFixture floor(int x0, int z0, int x1, int z1, int y, String material) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                grid.block(x, y, z, material);
            }
        }
        return this;
    }

    WalkFixture block(int x, int y, int z, String material) {
        grid.block(x, y, z, material);
        return this;
    }

    WalkFixture column(int x, int z, int yFrom, int yTo, String material) {
        grid.column(x, z, yFrom, yTo, material);
        return this;
    }

    WalkTerrain terrain() {
        return new WalkTerrain(grid, grid, WalkCells.ofMaterials(grid::material, Set.of(LADDER)));
    }

    /**
     * The player without the wall cost: the geometry fixtures test the walkability rules and their exact
     * costs; the wall cost has its own tests.
     */
    static final MovementProfile GEOMETRY_PLAYER = MovementProfile.PLAYER.withWallCost(0);

    /**
     * A request from the floor cell {@code (sx, sFloorY, sz)} (feet one above, block centre) to the
     * floor cell {@code (tx, tFloorY, tz)}, arriving only on that cell, {@link #GEOMETRY_PLAYER}, open access,
     * {@link #GEOMETRY} budget.
     */
    WalkRequest request(int sx, int sFloorY, int sz, int tx, int tFloorY, int tz) {
        return WalkRequest.toPoint(terrain(), sx + 0.5, sFloorY + 1.0, sz + 0.5, tx + 0.5, tFloorY, tz + 0.5, 0.25)
            .withBudget(GEOMETRY).withProfile(GEOMETRY_PLAYER);
    }

    WalkResult walk(int sx, int sFloorY, int sz, int tx, int tFloorY, int tz) {
        return new WalkSearch().find(request(sx, sFloorY, sz, tx, tFloorY, tz));
    }
}
