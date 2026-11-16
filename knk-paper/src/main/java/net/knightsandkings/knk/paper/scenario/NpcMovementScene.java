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
 * world): it covers 1407 49 -549 to 1418 79 -487 and is pasted in the frame box = real - (1400, 46, -515), so its
 * rows north of -515 and its blocks above y 69 fall outside the box. It starts at y 49, one block above the street's
 * floor, so the scene lays a stone floor at real y 48 under its footprint first. The jagged straight, the doorway and
 * the plaza are synthetic by design (4c.5 items 4 to 6).
 * <p>
 * Coordinates are the box's (x, z 0..31, y 0..23); floor points are the block the NPC stands on.
 */
public final class NpcMovementScene {

    /** The developer's schematic of the stair house (attached to #603, committed on 2026-11-15). */
    public static final String STAIRHOUSE = "knk-stairhouse.schem";
    /** Where the stair house's schematic goes in the box: its lowest corner (1407 49 -549) in the frame real - (1400, 46, -515). */
    static final int[] STAIRHOUSE_AT = {7, 3, -34};

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
                for (int z = Math.max(0, at[2]); z < Math.min(ScenarioScene.SIZE, at[2] + schematic.length()); z++) {
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
        return List.of(floor(13.5, HOUSE_GROUND, 1.5), floor(13.5, HOUSE_GROUND, 5.5), floor(13.0, HOUSE_GROUND, 7.5),
            floor(12.5, HOUSE_GROUND, 10.0), floor(12.5, HOUSE_GROUND, 12.5), floor(12.5, HOUSE_GROUND, 14.5),
            floor(12.5, HOUSE_GROUND, 15.5), floor(12.5, HOUSE_STEP, 16.5), floor(12.5, HOUSE_STEP, 18.5),
            floor(12.5, HOUSE_HALL, 20.5), floor(12.5, HOUSE_HALL, 23.5), floor(13.5, HOUSE_HALL, 25.5));
    }

    /** {@link #houseLine} there and back, the loop the scenario road walks. */
    public List<NavPoint> houseLoop() {
        return thereAndBack(houseLine());
    }

    /** Six places on the street north of the entrance (feet; real -514..-512). */
    public List<NavPoint> houseBottomSpots() {
        List<NavPoint> spots = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            spots.add(feet(10.5 + 1.2 * i, HOUSE_GROUND, 1.5 + (i % 3)));
        }
        return spots;
    }

    /** Six places in the hall at the top (feet 51; real 1412..1414, -491..-490). */
    public List<NavPoint> houseTopSpots() {
        List<NavPoint> spots = new ArrayList<>();
        for (int z = 24; z <= 25; z++) {
            for (int x = 12; x <= 14; x++) {
                spots.add(feet(x + 0.5, HOUSE_HALL, z + 0.5));
            }
        }
        return spots;
    }

    /** The group's start: the street north of the entrance (real -515..-511, 1409..1418), leader in front. */
    public NavPoint houseGroupSpot(int i) {
        return i == 0 ? feet(13.5, HOUSE_GROUND, 4.5)
            : feet(11.0 + 1.0 * ((i - 1) % 6), HOUSE_GROUND, 3.5 - 0.9 * ((i - 1) / 6));
    }

    /** The group's goal in the hall. */
    public NavPoint houseGoal() {
        return feet(13.5, HOUSE_HALL, 25.5);
    }

    /** Whether feet are in or just before or after the entrance (1411..1415, -510..-506). */
    public boolean inHouseEntrance(NavPoint feet) {
        int x = localX(feet);
        int z = localZ(feet);
        return x >= 11 && x <= 15 && z >= 5 && z <= 9 && localY(feet) <= HOUSE_GROUND + 1;
    }

    /** Whether feet are on the straight at 1414 49 -503 and the passage after it (-505..-500). */
    public boolean onHouseStraight(NavPoint feet) {
        int x = localX(feet);
        int z = localZ(feet);
        return x >= 9 && x <= 16 && z >= 10 && z <= 15 && localY(feet) <= HOUSE_GROUND + 1;
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
