package net.knightsandkings.knk.paper.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.type.Slab;
import net.knightsandkings.knk.core.navigation.shape.NavPoint;

/**
 * The two spots of the developer's L3 retest (PandiO/knk-workspace#592) rebuilt in their real geometry and orientation,
 * one per scenario box ({@value #SIZE} × {@value #HEIGHT} × {@value #SIZE}), heights shifted into the box:
 * <ol>
 *     <li><b>The stair corner</b> ({@link #buildStairCorner}, the #491 corner at 1411 73 -537; scene x = real x - 1400,
 *     z = real z + 556, floor = real floor - 65). A 2-wide flight comes south (z rising) along x 9..10 up to floor 7, a
 *     slab step at z 18, the corner square at floor 8 (x 9..10, z 19..20), then a 1-wide flight east along z 20
 *     with a slab at (12, 9, 20) climbing to floor 11. Everything round it is a 7-block drop to the ground (floor 1),
 *     so the cell (11, z 19) on the inside of the turn is air, not a wall.</li>
 *     <li><b>The landing</b> ({@link #buildLanding}, the #492 landing at 1433 44 -517; scene x = real x - 1408,
 *     z = real z + 526, floor = real floor - 40). A 3-wide flight comes north along x 24..26 at floor 3, a slab step at
 *     z 15, the approach at the landing's level (the developer's positions: feet 44 from about z -501 on), the landing
 *     at floor 4 (x 24..26, z 8..10) against the building's walls to its north (z 7) and east
 *     (x 27), a wooden floor overhanging the landing's corner (top slabs at y 7: 2.5 blocks of headroom), then a
 *     3-wide flight west along z 8..10, half a block per block, up to floor 8 and a top floor long enough for a column
 *     of 12 to stand past the landing.</li>
 * </ol>
 * Coordinates are relative to the box's lowest corner; the scenario host clears its box before and after.
 */
public final class NpcLedgeScene {

    public static final int SIZE = ScenarioScene.SIZE;
    public static final int HEIGHT = ScenarioScene.HEIGHT;
    /** The ground's floor (feet stand at 2). */
    public static final int GROUND = 1;
    /** The stair corner's floor (real y 73). */
    public static final int CORNER = 8;
    /** The landing's floor (real y 44). */
    public static final int LANDING = 4;

    private static final Material STONE = Material.STONE;
    private static final Material WALL = Material.STONE_BRICKS;

    private final World world;
    private final int ox;
    private final int oy;
    private final int oz;

    public NpcLedgeScene(World world, int originX, int originY, int originZ) {
        this.world = Objects.requireNonNull(world, "world");
        this.ox = originX;
        this.oy = originY;
        this.oz = originZ;
    }

    /** In a scenario's box. */
    public static NpcLedgeScene in(ScenarioScene scene) {
        return new NpcLedgeScene(scene.world(), scene.worldX(0), scene.worldY(0), scene.worldZ(0));
    }

    public World world() {
        return world;
    }

    // ==================== the stair corner (#491) ====================

    /** Builds the stair corner. */
    public void buildStairCorner() {
        fill(0, 0, 0, SIZE - 1, GROUND, SIZE - 1, STONE);
        for (int z = 7; z <= 11; z++) {
            column(9, 10, z, z, GROUND + (z - 6));       // the flight up from the ground: floor 2 at z 7 to 6 at z 11
        }
        column(9, 10, 12, 17, 7);                         // floor 7 (real 72)
        column(9, 10, 18, 18, 7);                         // the slab step (real -538)
        slab(9, 8, 18);
        slab(10, 8, 18);
        column(9, 10, 19, 20, CORNER);                    // the corner square, floor 8 (real 73)
        column(11, 11, 20, 20, CORNER);                   // (1411, 73, -536)
        int floor = CORNER;
        for (int x = 12; x <= 17; x++) {                  // east, a slab then a full block per step (real 1412 74 -536)
            if ((x - 12) % 2 == 0) {
                column(x, x, 20, 20, floor);
                slab(x, floor + 1, 20);
            } else {
                floor++;
                column(x, x, 20, 20, floor);
            }
        }
        column(18, 22, 20, 20, floor);                    // the top, floor 11
    }

    /** The stair corner's floor at the top of its east flight. */
    public int stairTop() {
        return CORNER + 3;
    }

    /**
     * The route there and back as the road line runs in the trace: down the flight's west column, its bend on the
     * block corner before the corner square, its next segment diagonally across the drop to the east flight.
     */
    public List<NavPoint> stairCuttingLine() {
        return thereAndBack(List.of(floor(9.5, GROUND, 3.5), floor(9.5, GROUND, 6.5), floor(9.5, 6, 11.5),
            floor(10.0, 7, 18.0), floor(17.5, stairTop(), 20.5), floor(21.5, stairTop(), 20.5)));
    }

    /** The route there and back through the cells' centres, the turn on the corner square's east column. */
    public List<NavPoint> stairCentreLine() {
        return thereAndBack(List.of(floor(10.5, GROUND, 3.5), floor(10.5, GROUND, 6.5), floor(10.5, 6, 11.5),
            floor(10.5, CORNER, 20.5), floor(21.5, stairTop(), 20.5)));
    }

    /** The bottom of the stair corner's route (feet). */
    public NavPoint stairBottom() {
        return feet(9.5, GROUND, 3.5);
    }

    /** The top of the stair corner's route (feet). */
    public NavPoint stairEnd() {
        return feet(21.5, stairTop(), 20.5);
    }

    /** Whether feet are on the ground beside the stair corner (under the turn): an NPC fell there. */
    public boolean belowTheCorner(NavPoint feet) {
        int x = feet.blockX() - ox;
        int z = feet.blockZ() - oz;
        return feet.blockY() - oy <= CORNER - 2 && x >= 8 && x <= 14 && z >= 16 && z <= 23;
    }

    // ==================== the landing (#492) ====================

    /** Builds the landing. */
    public void buildLanding() {
        fill(0, 0, 0, SIZE - 1, GROUND, SIZE - 1, STONE);
        column(24, 26, 16, 30, 3);                        // the flight's lower part, floor 3 (real 43)
        column(24, 26, 15, 15, 3);                        // the slab step before the landing
        for (int x = 24; x <= 26; x++) {
            slab(x, 4, 15);
        }
        column(24, 26, 11, 14, LANDING);                  // the approach at the landing's level (real feet 44)
        column(24, 26, 8, 10, LANDING);                   // the landing, floor 4 (real 44)
        fill(23, 2, 7, 28, LANDING + 5, 7, WALL);         // the building: north wall
        fill(27, 2, 7, 27, LANDING + 5, 30, WALL);        //               east wall, beside the flight
        for (int x = 25; x <= 26; x++) {                  // its wooden floor over the landing's corner: 2.5 free
            for (int z = 8; z <= 9; z++) {
                Slab top = (Slab) Material.OAK_SLAB.createBlockData();
                top.setType(Slab.Type.TOP);
                world.getBlockAt(ox + x, oy + LANDING + 3, oz + z).setBlockData(top, false);
            }
        }
        world.getBlockAt(ox + 26, oy + LANDING + 4, oz + 8).setType(Material.HOPPER, false); // the piece in its corner
        int floor = LANDING;
        for (int x = 23; x >= 16; x--) {                  // west, a slab then a full block per step (real 44 -> 48)
            if ((23 - x) % 2 == 0) {
                column(x, x, 8, 10, floor);
                for (int z = 8; z <= 10; z++) {
                    slab(x, floor + 1, z);
                }
            } else {
                floor++;
                column(x, x, 8, 10, floor);
            }
        }
        column(1, 15, 8, 10, floor);                      // the top, floor 8 (real 48)
    }

    /** The landing's floor at the top of the west flight. */
    public int landingTop() {
        return LANDING + 4;
    }

    /**
     * The route there and back, its turn cutting the inside of the landing's corner: the segment from the approach to
     * the west flight runs across the drop south-west of the landing (as a road line drawn round the landing would).
     */
    public List<NavPoint> landingLine() {
        return thereAndBack(List.of(floor(25.5, 3, 29.5), floor(25.5, LANDING, 13.0), floor(21.0, 6, 9.5),
            floor(16.5, landingTop(), 9.5), floor(2.5, landingTop(), 9.5)));
    }

    /** The bottom of the landing's route (feet). */
    public NavPoint landingBottom() {
        return feet(25.5, 3, 29.5);
    }

    /** The top of the landing's route (feet). */
    public NavPoint landingEnd() {
        return feet(2.5, landingTop(), 9.5);
    }

    /** Whether feet are past the landing: on the west flight or the top. */
    public boolean pastTheLanding(NavPoint feet) {
        return feet.blockX() - ox <= 22 && feet.blockY() - oy >= LANDING + 1;
    }

    /** Whether feet are on the landing or the approach to it (where the followers of #492 stopped). */
    public boolean onTheLanding(NavPoint feet) {
        int x = feet.blockX() - ox;
        int z = feet.blockZ() - oz;
        return x >= 23 && x <= 26 && z >= 8 && z <= 14;
    }

    // ==================== helpers ====================

    /** A floor point at a scene position. */
    public NavPoint floor(double x, int floorY, double z) {
        return new NavPoint(world.getName(), ox + x, oy + floorY, oz + z);
    }

    /** The feet position of an NPC standing on a scene floor. */
    public NavPoint feet(double x, int floorY, double z) {
        return new NavPoint(world.getName(), ox + x, oy + floorY + 1, oz + z);
    }

    private static List<NavPoint> thereAndBack(List<NavPoint> there) {
        List<NavPoint> loop = new ArrayList<>(there);
        for (int i = there.size() - 2; i >= 0; i--) {
            loop.add(there.get(i));
        }
        return loop;
    }

    /** Solid stone from above the ground up to {@code floorY} over x1..x2, z1..z2. */
    private void column(int x1, int x2, int z1, int z2, int floorY) {
        fill(x1, GROUND + 1, z1, x2, floorY, z2, STONE);
    }

    private void slab(int x, int y, int z) {
        Slab data = (Slab) Material.STONE_SLAB.createBlockData();
        data.setType(Slab.Type.BOTTOM);
        world.getBlockAt(ox + x, oy + y, oz + z).setBlockData(data, false);
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material material) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    world.getBlockAt(ox + x, oy + y, oz + z).setType(material, false);
                }
            }
        }
    }
}
