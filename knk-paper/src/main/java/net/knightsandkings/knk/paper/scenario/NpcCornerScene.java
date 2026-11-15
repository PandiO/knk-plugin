package net.knightsandkings.knk.paper.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.type.Slab;

import net.knightsandkings.knk.core.npc.nav.NavPoint;

/**
 * The corner test scene of implementation plan 4b.2 item 19 (PandiO/knk-workspace#521): one loop of about 90 blocks
 * round a building, in a box of {@value #SIZE} × {@value #HEIGHT} × {@value #SIZE} blocks (one scenario scene), with
 * the hard spots of the developer's tests on {@code quarry-to-keep}:
 * <ol>
 *     <li>north: a 3-wide road (z 4..6) between the outer wall and the building, the route line hugging the outer
 *     wall (a straight along a wall is left alone);</li>
 *     <li>the north-east turn: an inside corner of the outer walls and the building's corner on the inside of the turn
 *     (an outside corner round a building);</li>
 *     <li>east: a 2-wide passage (z 10..14) and a 1-wide one (z 17..20), also L4's narrowing test;</li>
 *     <li>south: a 2-wide flight up from the ground (floor 1) to a ledge at floor 8, a slab before the ledge's corner
 *     and a slab beside it, a 7-block drop on both sides (the 1411 73 -537 stair corner);</li>
 *     <li>west: the ledge north to a landing with 2.5 blocks of headroom under an overhanging slab floor and a slab
 *     step before it (the 1433 44 -517 landing), then a flight down to the ground.</li>
 * </ol>
 * Coordinates are relative to the box's lowest corner. The scene builds on any box; the scenario host clears its box
 * before and after, the dev command ({@code /knk npc nav scene corners}) clears the box itself first.
 */
public final class NpcCornerScene {

    public static final int SIZE = ScenarioScene.SIZE;
    public static final int HEIGHT = 16;
    /** The ground floor's height (feet stand at 2). */
    public static final int GROUND = 1;
    /** The ledge's floor. */
    public static final int LEDGE = 8;

    private static final Material WALL = Material.STONE_BRICKS;
    private static final Material FLOOR = Material.STONE;

    private final World world;
    private final int ox;
    private final int oy;
    private final int oz;

    public NpcCornerScene(World world, int originX, int originY, int originZ) {
        this.world = Objects.requireNonNull(world, "world");
        this.ox = originX;
        this.oy = originY;
        this.oz = originZ;
    }

    /** In a scenario's box. */
    public static NpcCornerScene in(ScenarioScene scene) {
        return new NpcCornerScene(scene.world(), scene.worldX(0), scene.worldY(0), scene.worldZ(0));
    }

    public World world() {
        return world;
    }

    /** Clears the box to air (the dev command; a scenario's box is cleared by the host). */
    public void clear() {
        fill(0, 0, 0, SIZE - 1, HEIGHT - 1, SIZE - 1, Material.AIR);
    }

    /** Builds the scene. */
    public void build() {
        fill(0, 0, 0, SIZE - 1, GROUND, SIZE - 1, FLOOR); // the ground: floor at y 1
        // outer walls, three high, except where the ledge runs above them (the drop must stay a drop)
        fill(3, 2, 3, 28, 4, 3, WALL);                       // north
        fill(28, 2, 3, 28, 4, 28, WALL);                     // east
        fill(17, 2, 28, 28, 4, 28, WALL);                    // south, east of the flight
        fill(3, 2, 3, 3, 4, 11, WALL);                       // west, north of the landing
        // the building in the middle, four high
        fill(7, 2, 7, 24, 5, 24, WALL);
        // east: a 2-wide passage (x 25..26) and a 1-wide one (x 26)
        fill(27, 2, 10, 27, 4, 14, WALL);
        fill(25, 2, 17, 25, 4, 20, WALL);
        fill(27, 2, 17, 27, 4, 20, WALL);
        // south: the flight up, 2 wide (z 26..27), floor 2 at x 16 up to floor 7 at x 11, then the ledge at floor 8
        for (int x = 16; x >= 11; x--) {
            fill(x, GROUND + 1, 26, x, GROUND + (17 - x), 27, FLOOR);
        }
        fill(4, GROUND + 1, 26, 10, LEDGE, 27, FLOOR);           // the south ledge, x 4..10
        fill(4, GROUND + 1, 12, 5, LEDGE, 25, FLOOR);            // the west ledge, z 12..25
        slab(7, LEDGE + 1, 27, Slab.Type.BOTTOM);                // a slab before the corner (outer lane)
        slab(5, LEDGE + 1, 25, Slab.Type.BOTTOM);                // and one beside it, just past the turn
        // west: a slab step before the landing, the landing at z 12..13 under an overhang (2.5 free), then down
        slab(4, LEDGE + 1, 14, Slab.Type.BOTTOM);
        slab(5, LEDGE + 1, 14, Slab.Type.BOTTOM);
        for (int x = 3; x <= 6; x++) {
            for (int z = 11; z <= 13; z++) {
                slab(x, LEDGE + 3, z, Slab.Type.TOP);
            }
        }
        for (int z = 11; z >= 6; z--) {
            fill(4, GROUND + 1, z, 5, GROUND + (z - 5), z, FLOOR); // floor 7 at z 11 down to floor 2 at z 6
        }
    }

    /** The route line as floor points (world coordinates), once round the loop from the start to the start. */
    public List<NavPoint> routeLine() {
        List<NavPoint> line = new ArrayList<>();
        line.add(floor(6.5, GROUND, 4.5));
        line.add(floor(26.5, GROUND, 4.5));   // the north-east turn
        line.add(floor(26.5, GROUND, 26.5));  // through the 2- and 1-wide passages, the south-east turn
        line.add(floor(17.5, GROUND, 26.5));  // the foot of the flight
        line.add(floor(11.5, 7, 26.5));       // its top
        line.add(floor(10.5, LEDGE, 26.5));
        line.add(floor(4.5, LEDGE, 26.5));    // the stair corner on the ledge
        line.add(floor(4.5, LEDGE, 12.5));    // the landing under the overhang
        line.add(floor(4.5, 7, 11.5));
        line.add(floor(4.5, GROUND + 1, 6.5)); // the foot of the flight down
        line.add(floor(4.5, GROUND, 5.5));
        line.add(floor(6.5, GROUND, 4.5));
        return line;
    }

    /** Where NPCs start: on the north road. */
    public NavPoint start() {
        return feet(6.5, GROUND, 4.5);
    }

    /** The stair corner on the ledge (feet), 1411 73 -537 in the dev world. */
    public NavPoint stairCorner() {
        return feet(4.5, LEDGE, 26.5);
    }

    /** The landing under the overhang (feet), 1433 44 -517 in the dev world. */
    public NavPoint landing() {
        return feet(4.5, LEDGE, 12.5);
    }

    /** A cell of the 1-wide passage (feet). */
    public NavPoint narrowPassage() {
        return feet(26.5, GROUND, 18.5);
    }

    /** A floor point at a scene position. */
    public NavPoint floor(double x, int floorY, double z) {
        return new NavPoint(world.getName(), ox + x, oy + floorY, oz + z);
    }

    /** The feet position of an NPC standing on a scene floor. */
    public NavPoint feet(double x, int floorY, double z) {
        return new NavPoint(world.getName(), ox + x, oy + floorY + 1, oz + z);
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

    private void slab(int x, int y, int z, Slab.Type type) {
        Slab data = (Slab) Material.STONE_SLAB.createBlockData();
        data.setType(type);
        world.getBlockAt(ox + x, oy + y, oz + z).setBlockData(data, false);
    }
}
