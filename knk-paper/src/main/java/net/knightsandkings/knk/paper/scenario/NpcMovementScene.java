package net.knightsandkings.knk.paper.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.knightsandkings.knk.core.npc.nav.NavPoint;

/**
 * The places of the NPC movement scenarios (implementation plan 4c.5, link M1, PandiO/knk-workspace#621), each a
 * WorldEdit schematic pasted into the scenario's box ({@link SceneSchematic}) with the points the scenarios use: the
 * road line as the road graph would give it, where NPCs start and where they go, and the areas the checks watch.
 * <p>
 * The stair house is the developer's schematic {@value #STAIRHOUSE} (PandiO/knk-workspace#603, copied in the dev
 * world): it covers 1407 49 -549 to 1418 79 -487 with both flights and the upper landings. Link B1
 * (PandiO/knk-workspace#670) pastes all of it in a box of its own, {@link #STAIRHOUSE_BOX}, in the frame box = real -
 * (1400, 46, -553) ({@link #STAIRHOUSE_FRAME}); link M1's box (real 1400..1431, 46..69, -515..-484) held only its lower
 * part. It starts at y 49, one block above the street's floor, so the scene lays a stone floor at real y 48 under its
 * footprint first, and on the street east of it (real 1419..1431, -530..-510), where the real route comes in. The
 * jagged straight, the doorway and the plaza are synthetic by design (4c.5 items 4 to 6).
 * <p>
 * Coordinates are the box's (x, z 0..31, y 0..23 in the default box; the stair house's box is 32 x 40 x 72); floor
 * points are the block the NPC stands on.
 */
public final class NpcMovementScene {

    /** The developer's schematic of the stair house (attached to #603, committed on 2026-11-15). */
    public static final String STAIRHOUSE = "knk-stairhouse.schem";
    /** The stair house's box (PandiO/knk-workspace#670): real 1400..1431, 46..85, -553..-482. */
    public static final ScenarioScene.Box STAIRHOUSE_BOX = new ScenarioScene.Box(32, 40, 72);
    /** The real position of the stair house box's lowest corner: box = real - this. */
    public static final int[] STAIRHOUSE_FRAME = {1400, 46, -553};
    /** Where the stair house's schematic goes in the box: its lowest corner (1407 49 -549). */
    static final int[] STAIRHOUSE_AT = {7, 3, 4};
    /**
     * Link M1's points were given in its frame, real - (1400, 46, -515); in this box they lie this many blocks further
     * along z.
     */
    static final int M1_Z = 38;

    private final ScenarioScene scene;
    private final String resource;

    private NpcMovementScene(ScenarioScene scene, String resource) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.resource = resource;
    }

    /** The stair house, its entrance at 1413 49 -508 and the 3-wide straight at 1414 49 -503 (4c.5 items 1 to 3). */
    public static NpcMovementScene stairHouse(ScenarioScene scene) {
        return new NpcMovementScene(scene, STAIRHOUSE);
    }

    /** A 3-wide straight with one-block notches, a lantern on a post and a stair block beside it (4c.5 item 4). */
    public static NpcMovementScene jaggedStraight(ScenarioScene scene) {
        return new NpcMovementScene(scene, "npc-jagged-straight.schem");
    }

    /** Two rooms and a one-block doorway between them (4c.5 item 5). */
    public static NpcMovementScene doorway(ScenarioScene scene) {
        return new NpcMovementScene(scene, "npc-doorway.schem");
    }

    /** A 15 × 15 plaza with four roads and a well off-centre (4c.5 item 6). */
    public static NpcMovementScene plaza(ScenarioScene scene) {
        return new NpcMovementScene(scene, "npc-plaza.schem");
    }

    /** Pastes the scene into the box; returns what was built, for the report. */
    public String build() {
        SceneSchematic schematic = SceneSchematic.fromResource(resource);
        int[] at = pasteAt();
        if (STAIRHOUSE.equals(resource)) {
            // the street's floor (real y 48) under the schematic's footprint: the copy starts at y 49
            for (int x = at[0]; x < at[0] + schematic.width(); x++) {
                for (int z = Math.max(0, at[2]); z < Math.min(scene.box().z(), at[2] + schematic.length()); z++) {
                    for (int y = 0; y < at[1]; y++) {
                        scene.block(x, y, z).setType(org.bukkit.Material.STONE, false);
                    }
                }
            }
            // the street east of the house, where the real route comes in (link B1): real 1419..1431, -530..-510
            for (int x = at[0] + schematic.width(); x < scene.box().x(); x++) {
                for (int z = -530 - STAIRHOUSE_FRAME[2]; z <= -510 - STAIRHOUSE_FRAME[2]; z++) {
                    for (int y = 0; y < at[1]; y++) {
                        scene.block(x, y, z).setType(org.bukkit.Material.STONE, false);
                    }
                }
            }
        }
        SceneSchematic.Pasted pasted = schematic.paste(scene, at[0], at[1], at[2]);
        return resource + ", " + pasted.placed() + " blocks"
            + (pasted.outsideTheBox() > 0 ? ", " + pasted.outsideTheBox() + " outside the box" : "");
    }

    /** The schematic this scene is built from. */
    public String resource() {
        return resource;
    }

    /** Where the schematic's lowest corner goes in the box. */
    public int[] pasteAt() {
        return STAIRHOUSE.equals(resource) ? STAIRHOUSE_AT.clone() : new int[] {0, 0, 0};
    }

    // ==================== the stair house (the developer's schematic) ====================

    /** The street's floor (real y 48: feet 49). */
    public static final int HOUSE_GROUND = 2;
    /** The floor after the first steps (real 49). */
    public static final int HOUSE_STEP = 3;
    /** The hall at the top of the second step (real 50: feet 51). */
    public static final int HOUSE_HALL = 4;

    /**
     * The road line from the street north of the stair house through the entrance (1413 -508), along the straight at
     * 1414 -503 and the 3-wide passage (1411..1413, -502..-500), up the two steps (-499, -495) to the hall at feet 51
     * (-492..-490). Hand-drawn on the developer's schematic along the walkable middle, as the road graph's line would
     * run; replace it with the developer's trace when one is posted.
     */
    public List<NavPoint> houseLine() {
        return List.of(m1Floor(13.5, HOUSE_GROUND, 1.5), m1Floor(13.5, HOUSE_GROUND, 5.5), m1Floor(13.0, HOUSE_GROUND, 7.5),
            m1Floor(12.5, HOUSE_GROUND, 10.0), m1Floor(12.5, HOUSE_GROUND, 12.5), m1Floor(12.5, HOUSE_GROUND, 14.5),
            m1Floor(12.5, HOUSE_GROUND, 15.5), m1Floor(12.5, HOUSE_STEP, 16.5), m1Floor(12.5, HOUSE_STEP, 18.5),
            m1Floor(12.5, HOUSE_HALL, 20.5), m1Floor(12.5, HOUSE_HALL, 23.5), m1Floor(13.5, HOUSE_HALL, 25.5));
    }

    /** {@link #houseLine} there and back, the loop the scenario road walks. */
    public List<NavPoint> houseLoop() {
        return thereAndBack(houseLine());
    }

    /** Six places on the street north of the entrance (feet; real -514..-512). */
    public List<NavPoint> houseBottomSpots() {
        List<NavPoint> spots = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            spots.add(feet(10.5 + 1.2 * i, HOUSE_GROUND, M1_Z + 1.5 + (i % 3)));
        }
        return spots;
    }

    /** Six places in the hall at the top (feet 51; real 1412..1414, -491..-490). */
    public List<NavPoint> houseTopSpots() {
        List<NavPoint> spots = new ArrayList<>();
        for (int z = 24; z <= 25; z++) {
            for (int x = 12; x <= 14; x++) {
                spots.add(feet(x + 0.5, HOUSE_HALL, M1_Z + z + 0.5));
            }
        }
        return spots;
    }

    /** The group's start: the street north of the entrance (real -515..-511, 1409..1418), leader in front. */
    public NavPoint houseGroupSpot(int i) {
        return i == 0 ? feet(13.5, HOUSE_GROUND, M1_Z + 4.5)
            : feet(11.0 + 1.0 * ((i - 1) % 6), HOUSE_GROUND, M1_Z + 3.5 - 0.9 * ((i - 1) / 6));
    }

    /** The group's goal in the hall. */
    public NavPoint houseGoal() {
        return feet(13.5, HOUSE_HALL, M1_Z + 25.5);
    }

    /** Whether feet are in or just before or after the entrance (1411..1415, -510..-506). */
    public boolean inHouseEntrance(NavPoint feet) {
        int x = localX(feet);
        int z = localZ(feet) - M1_Z;
        return x >= 11 && x <= 15 && z >= 5 && z <= 9 && localY(feet) <= HOUSE_GROUND + 1;
    }

    /** Whether feet are on the straight at 1414 49 -503 and the passage after it (-505..-500). */
    public boolean onHouseStraight(NavPoint feet) {
        int x = localX(feet);
        int z = localZ(feet) - M1_Z;
        return x >= 9 && x <= 16 && z >= 10 && z <= 15 && localY(feet) <= HOUSE_GROUND + 1;
    }

    // ==================== the climb through the whole stair house (link B1, PandiO/knk-workspace#670) ====================

    /**
     * The real road line through the whole stair house (link B1, PandiO/knk-workspace#670): the {@code # waypoints:}
     * lines of the developer's traces of 2026-11-16 (knk-plugin {@code evidence/smoke-2026-11-16}, copied to
     * {@code src/test/resources/scenario/evidence/2026-11-16/}), as feet blocks (x, y, z) in the order they were handed
     * out going up: the entrance from the east from trace 69 (walked down, reversed), the hall to the landing at -536
     * from trace 87 (walked up), the top landing and hall from trace 63. Duplicates where two stretches join are left
     * out. East of the house (x over 1418) the real street falls to 1428 47 -516 and 1433 44 -516, beyond the
     * schematic, so the scene starts on its own flat street at 1426 49 -516 instead. The line's heights are the whole
     * blocks the route follower gave (decision 34.2.2: the flights' entries); north of the top hall the real line goes on
     * to the keep (1415 76 -550, 1399 81 -530), outside the schematic.
     */
    static final int[][] CLIMB = {
        {1426, 49, -516}, // the scene's own flat street east of the house (real: 1428 47 -516)
        {1412, 49, -511}, {1412, 49, -500}, {1412, 50, -498}, // trace 69, reversed: the entrance and the passage
        {1412, 50, -496}, {1412, 51, -491}, {1409, 52, -493}, {1409, 54, -498}, {1411, 55, -503}, {1414, 55, -503},
        {1415, 55, -501}, {1415, 56, -499}, {1415, 57, -495}, {1415, 58, -493}, {1414, 59, -489}, {1410, 59, -489},
        {1409, 60, -493}, {1409, 61, -494}, {1409, 62, -499}, {1410, 63, -503}, {1413, 64, -503}, {1415, 64, -505},
        {1415, 65, -510}, {1415, 66, -516}, {1415, 66, -518}, {1415, 66, -535}, {1415, 67, -539}, {1415, 68, -540},
        {1416, 68, -540}, {1416, 69, -543}, {1414, 70, -547}, {1409, 71, -546}, {1409, 72, -543}, {1409, 74, -538},
        {1409, 74, -536}, // trace 87: the hall, flights 1 to 5
        {1410, 74, -535}, {1413, 75, -535}, {1415, 75, -536}, {1415, 76, -546}, // trace 63: the top landing and hall
    };

    /**
     * The flights and landings of the climb, in the order the line passes them (real feet blocks, inclusive): name,
     * x min, x max, z min, z max, feet y min, feet y max. A body on none of them is between them (a fall shows as the
     * next place lower than the last).
     */
    static final Object[][] CLIMB_PLACES = {
        {"street and hall", 1400, 1431, -530, -487, 49, 51},
        {"flight 1 (x 1409, north)", 1408, 1410, -500, -491, 52, 55},
        {"landing -501", 1408, 1416, -502, -501, 55, 55},
        {"flight 2 (x 1415, south)", 1414, 1416, -500, -491, 56, 59},
        {"landing -490", 1408, 1416, -490, -489, 59, 59},
        {"flight 3 (x 1409, north)", 1408, 1410, -502, -491, 60, 63},
        {"landing -503", 1408, 1416, -505, -503, 63, 64},
        {"corridor over the street", 1414, 1416, -533, -506, 65, 66},
        {"flight 4 (x 1415, north)", 1414, 1416, -545, -534, 66, 70},
        {"landing -546", 1408, 1416, -547, -546, 70, 71},
        {"flight 5 (x 1409, south)", 1408, 1410, -545, -537, 71, 74},
        {"landing -536", 1408, 1416, -536, -536, 74, 75},
        {"top hall", 1408, 1416, -548, -537, 75, 77},
    };

    /** The real road line from the street east of the house through the entrance and up all five flights (floor points). */
    public List<NavPoint> climbLine() {
        List<NavPoint> line = new ArrayList<>();
        for (int[] feet : CLIMB) {
            line.add(real(feet[0] + 0.5, feet[1] - 1, feet[2] + 0.5));
        }
        return line;
    }

    /** {@link #climbLine} there and back. */
    public List<NavPoint> climbLoop() {
        return thereAndBack(climbLine());
    }

    /** The climb's start on the street east of the house (feet, real 1428.5 49 -520.5), and a group's places behind it. */
    public NavPoint climbStart(int i) {
        if (i == 0) {
            return realFeet(1427.5, 48, -520.5);
        }
        // followers in rows of 3 further east on the street (real 1428..1430, -522..-519)
        return realFeet(1428.5 + 0.9 * ((i - 1) / 4 % 3), 48, -521.75 + 0.85 * ((i - 1) % 4));
    }

    /**
     * Singles sent with a group: on the street east of the house beside the group's places, ten south of it (real
     * 1425..1430, -528..-527) and ten north of it (-516..-515).
     */
    public NavPoint climbBeside(int i) {
        int row = i / 5;
        double z = row < 2 ? -527.5 + row : -515.5 + (row - 2);
        return realFeet(1425.5 + 1.2 * (i % 5), 48, z);
    }

    /** The climb's goal in the top hall, the line's last point in the house (feet, real 1415.5 76 -545.5). */
    public NavPoint climbGoal() {
        return realFeet(1415.5, 75, -545.5);
    }

    /** The place of the climb a body's feet are on ({@link #CLIMB_PLACES} index), or -1 between them. */
    public int climbPlace(NavPoint feet) {
        int x = localX(feet) + STAIRHOUSE_FRAME[0];
        int y = (int) Math.floor(feet.y() - scene.worldY(0) + 0.01) + STAIRHOUSE_FRAME[1];
        int z = localZ(feet) + STAIRHOUSE_FRAME[2];
        for (int i = CLIMB_PLACES.length - 1; i >= 0; i--) {
            Object[] p = CLIMB_PLACES[i];
            if (x >= (int) p[1] && x <= (int) p[2] && z >= (int) p[3] && z <= (int) p[4] && y >= (int) p[5]
                && y <= (int) p[6]) {
                return i;
            }
        }
        return -1;
    }

    /** A place's name. */
    public static String climbPlaceName(int place) {
        return place < 0 ? "between places" : (String) CLIMB_PLACES[place][0];
    }

    /** The number of places on the climb. */
    public static int climbPlaces() {
        return CLIMB_PLACES.length;
    }

    /** A real floor block's centre as a floor point (the stair house's box only). */
    public NavPoint real(double x, int floorY, double z) {
        return floor(x - STAIRHOUSE_FRAME[0], floorY - STAIRHOUSE_FRAME[1], z - STAIRHOUSE_FRAME[2]);
    }

    /** The feet of a body standing on a real floor block (the stair house's box only). */
    public NavPoint realFeet(double x, int floorY, double z) {
        return feet(x - STAIRHOUSE_FRAME[0], floorY - STAIRHOUSE_FRAME[1], z - STAIRHOUSE_FRAME[2]);
    }

    /** "x y z" of a feet position in real coordinates (the stair house's box only). */
    public String realBlock(NavPoint feet) {
        return (localX(feet) + STAIRHOUSE_FRAME[0]) + " " + (feet.blockY() - scene.worldY(0) + STAIRHOUSE_FRAME[1]) + " "
            + (localZ(feet) + STAIRHOUSE_FRAME[2]);
    }

    /** "name at x y z" with world block coordinates turned into real ones (the stair house's box only). */
    public String realPosition(String who) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(.*) at (-?\\d+) (-?\\d+) (-?\\d+)$").matcher(who);
        if (!m.matches()) {
            return who;
        }
        return m.group(1) + " at " + (Integer.parseInt(m.group(2)) - scene.worldX(0) + STAIRHOUSE_FRAME[0]) + " "
            + (Integer.parseInt(m.group(3)) - scene.worldY(0) + STAIRHOUSE_FRAME[1]) + " "
            + (Integer.parseInt(m.group(4)) - scene.worldZ(0) + STAIRHOUSE_FRAME[2]);
    }

    /** A floor point given in link M1's frame (real - (1400, 46, -515)). */
    private NavPoint m1Floor(double x, int floorY, double z) {
        return floor(x, floorY, M1_Z + z);
    }

    // ==================== the jagged straight ====================

    /** The jagged straight's floor. */
    public static final int FLAT = 1;

    /**
     * The road line down the jagged straight as the road graph's skeleton gives it: through the middle of the local
     * free width at every block, so it wobbles half a block towards each notch.
     */
    public List<NavPoint> jaggedLine() {
        List<Integer> east = List.of(4, 7, 9, 12, 15, 17, 20, 23, 25, 28);
        List<Integer> west = List.of(3, 5, 8, 11, 13, 16, 19, 21, 24, 27);
        List<NavPoint> line = new ArrayList<>();
        for (int z = 0; z <= 31; z++) {
            int min = west.contains(z) ? 13 : 14;
            int max = east.contains(z) && z != 12 ? 17 : 16; // the notch at z 12 holds the lantern post
            line.add(floor((min + max + 1) / 2.0, FLAT, z + 0.5));
        }
        return line;
    }

    public List<NavPoint> jaggedLoop() {
        return thereAndBack(jaggedLine());
    }

    /** Six places at one end of the straight (feet): north (z 1..2) or south (z 29..30). */
    public List<NavPoint> jaggedEnd(boolean north) {
        List<NavPoint> spots = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            spots.add(feet(14.5 + (i % 3), FLAT, north ? 1.5 + (i / 3) : 30.5 - (i / 3)));
        }
        return spots;
    }

    // ==================== the doorway ====================

    /** The doorway's cell (local 12 7). */
    public static final int DOOR_X = 12;
    public static final int DOOR_Z = 7;

    /** The road line from the west room's middle through the doorway to the east room's middle. */
    public List<NavPoint> doorLine() {
        return List.of(floor(7.5, FLAT, 7.5), floor(11.5, FLAT, 7.5), floor(12.5, FLAT, 7.5), floor(13.5, FLAT, 7.5),
            floor(17.5, FLAT, 7.5));
    }

    public List<NavPoint> doorLoop() {
        return thereAndBack(doorLine());
    }

    /** Six places in the west room ({@code west}) or the east room, mirrored about the doorway (feet). */
    public List<NavPoint> doorRoom(boolean west) {
        List<NavPoint> spots = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            double x = 4.5 + 2 * (i % 2);
            double z = 4.5 + 3 * (i / 2);
            spots.add(feet(west ? x : 24 - x, FLAT, z));
        }
        return spots;
    }

    /** Whether feet stand in the doorway's cell. */
    public boolean inDoorway(NavPoint feet) {
        return localX(feet) == DOOR_X && localZ(feet) == DOOR_Z;
    }

    // ==================== the plaza ====================

    /** The plaza's cells: x and z 8..22. */
    public static final int PLAZA_MIN = 8;
    public static final int PLAZA_MAX = 22;

    /**
     * The road line from the west road across the plaza to the north road as the road graph gives it today: to the
     * plaza junction in the middle and out again (plan 4c.1 item 6, a V instead of the chord).
     */
    public List<NavPoint> plazaLine() {
        return List.of(floor(1.5, FLAT, 15.5), floor(7.5, FLAT, 15.5), floor(15.5, FLAT, 15.5), floor(15.5, FLAT, 7.5),
            floor(15.5, FLAT, 1.5));
    }

    public List<NavPoint> plazaLoop() {
        return thereAndBack(plazaLine());
    }

    /** The plaza junction in the middle (box 15.5 15.5), as the road graph's plaza node: its open floor is measured. */
    public net.knightsandkings.knk.core.npc.nav.RoadPlanner.PlazaCrossing plazaJunction() {
        return new net.knightsandkings.knk.core.npc.nav.RoadPlanner.PlazaCrossing(floor(15.5, FLAT, 15.5), 0);
    }

    /** The horizontal distance from feet to the plaza junction. */
    public double fromJunction(NavPoint feet) {
        return Math.hypot(feet.x() - scene.worldX(0) - 15.5, feet.z() - scene.worldZ(0) - 15.5);
    }

    /**
     * The shortest line on the plaza from {@code a} to {@code b} that keeps {@code clearance} from the well (link M5's
     * reference: the chord where it clears the well, else the way round it touching the clearance), in blocks.
     */
    public double clearChord(NavPoint a, NavPoint b, double clearance) {
        double ox = scene.worldX(0);
        double oz = scene.worldZ(0);
        return PlazaGeometry.shortestRound(a.x() - ox, a.z() - oz, b.x() - ox, b.z() - oz, 11, 11, 13, 13, clearance);
    }

    /** Whether feet are on the plaza. */
    public boolean onPlaza(NavPoint feet) {
        double x = feet.x() - scene.worldX(0);
        double z = feet.z() - scene.worldZ(0);
        return x >= PLAZA_MIN && x < PLAZA_MAX + 1 && z >= PLAZA_MIN && z < PLAZA_MAX + 1;
    }

    /** The horizontal distance from feet to the well (x and z 11..12, a 2 × 2 block). */
    public double fromWell(NavPoint feet) {
        double x = feet.x() - scene.worldX(0);
        double z = feet.z() - scene.worldZ(0);
        double dx = Math.max(Math.max(11 - x, 0), x - 13);
        double dz = Math.max(Math.max(11 - z, 0), z - 13);
        return Math.hypot(dx, dz);
    }

    // ==================== helpers ====================

    /** A floor point at a box position. */
    public NavPoint floor(double x, int floorY, double z) {
        return new NavPoint(scene.world().getName(), scene.worldX(0) + x, scene.worldY(0) + floorY, scene.worldZ(0) + z);
    }

    /** The feet position of an NPC standing on a box floor. */
    public NavPoint feet(double x, int floorY, double z) {
        return new NavPoint(scene.world().getName(), scene.worldX(0) + x, scene.worldY(0) + floorY + 1,
            scene.worldZ(0) + z);
    }

    /** A feet position's block in box coordinates, {@code "x y z"}. */
    public String local(NavPoint feet) {
        return localX(feet) + " " + localY(feet) + " " + localZ(feet);
    }

    private int localX(NavPoint feet) {
        return feet.blockX() - scene.worldX(0);
    }

    private int localY(NavPoint feet) {
        return feet.blockY() - scene.worldY(0) - 1; // the floor's height
    }

    private int localZ(NavPoint feet) {
        return feet.blockZ() - scene.worldZ(0);
    }

    private static List<NavPoint> thereAndBack(List<NavPoint> there) {
        List<NavPoint> loop = new ArrayList<>(there);
        for (int i = there.size() - 2; i >= 0; i--) {
            loop.add(there.get(i));
        }
        return loop;
    }
}
