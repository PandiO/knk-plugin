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
 * Until the developer's schematic of the stair house is posted on PandiO/knk-workspace#603, the stair house is the
 * synthetic stand-in {@value #STAIRHOUSE_STANDIN} (built from the plan's description, in the real place: local = real
 * - (1400, 46, -515)); the jagged straight, the doorway and the plaza are synthetic by design (4c.5 items 4 to 6).
 * When the developer's file arrives as {@value #STAIRHOUSE_DEVELOPER}, its road line and points are taken from its
 * geometry and the trace on #603, and {@link #stairHouse} switches to it.
 * <p>
 * Coordinates are the box's (x, z 0..31, y 0..23); floor points are the block the NPC stands on.
 */
public final class NpcMovementScene {

    /** The stand-in stair house (tools/scenario-scenes/make_standins.py). */
    public static final String STAIRHOUSE_STANDIN = "npc-stairhouse-standin.schem";
    /** The developer's schematic, once posted on #603 and committed (not here yet). */
    public static final String STAIRHOUSE_DEVELOPER = "knk-stairhouse.schem";

    private final ScenarioScene scene;
    private final String resource;

    private NpcMovementScene(ScenarioScene scene, String resource) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.resource = resource;
    }

    /** The stair house, its entrance at 1413 49 -508 and the 3-wide straight at 1414 49 -503 (4c.5 items 1 to 3). */
    public static NpcMovementScene stairHouse(ScenarioScene scene) {
        return new NpcMovementScene(scene, STAIRHOUSE_STANDIN);
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
        SceneSchematic.Pasted pasted = schematic.paste(scene, 0, 0, 0);
        return resource + (STAIRHOUSE_STANDIN.equals(resource) ? " (stand-in until the developer's schematic)" : "")
            + ", " + pasted.placed() + " blocks";
    }

    /** The schematic this scene is built from. */
    public String resource() {
        return resource;
    }

    /** Whether it is a stand-in for a real place (the stair house before the developer's schematic). */
    public boolean standIn() {
        return STAIRHOUSE_STANDIN.equals(resource);
    }

    // ==================== the stair house (stand-in) ====================

    /** The ground floor's height (real y 48: feet 49). */
    public static final int HOUSE_GROUND = 2;
    /** The landing between the flights. */
    public static final int HOUSE_LANDING = 10;
    /** The top landing. */
    public static final int HOUSE_TOP = 17;

    /**
     * The road line from the road east of the house through the entrance, along the 3-wide straight (wobbling towards
     * its notches as a skeleton line does, plan 4c.1 item 2), up the first flight, across the landing and up the
     * second flight to the top landing.
     */
    public List<NavPoint> houseLine() {
        return List.of(floor(28.5, HOUSE_GROUND, 3.5), floor(14.5, HOUSE_GROUND, 3.5), floor(13.5, HOUSE_GROUND, 6.0),
            floor(13.5, HOUSE_GROUND, 7.5), floor(14.5, HOUSE_GROUND, 9.0), floor(15.2, HOUSE_GROUND, 10.5),
            floor(14.3, HOUSE_GROUND, 12.5), floor(14.5, HOUSE_GROUND, 13.5), floor(14.5, HOUSE_LANDING, 21.5),
            floor(14.5, HOUSE_LANDING, 23.5), floor(10.5, HOUSE_LANDING, 23.5), floor(10.5, HOUSE_LANDING + 1, 21.5),
            floor(10.5, HOUSE_TOP, 15.5), floor(10.5, HOUSE_TOP, 13.5));
    }

    /** {@link #houseLine} there and back, the loop the scenario road walks. */
    public List<NavPoint> houseLoop() {
        return thereAndBack(houseLine());
    }

    /** Six places on the road east of the house (feet). */
    public List<NavPoint> houseBottomSpots() {
        List<NavPoint> spots = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            spots.add(feet(20.5 + 1.5 * i, HOUSE_GROUND, 2.5 + (i % 3)));
        }
        return spots;
    }

    /** The six cells of the top landing (feet). */
    public List<NavPoint> houseTopSpots() {
        List<NavPoint> spots = new ArrayList<>();
        for (int z = 13; z <= 14; z++) {
            for (int x = 9; x <= 11; x++) {
                spots.add(feet(x + 0.5, HOUSE_TOP, z + 0.5));
            }
        }
        return spots;
    }

    /** Whether feet are in or just before or after the entrance (the doorway at local 13 7, real 1413 -508). */
    public boolean inHouseEntrance(NavPoint feet) {
        int x = localX(feet);
        int z = localZ(feet);
        return x >= 12 && x <= 16 && z >= 5 && z <= 9 && localY(feet) <= HOUSE_GROUND + 2;
    }

    /** Whether feet are on the 3-wide straight at 1414 49 -503 (local z 8..12). */
    public boolean onHouseStraight(NavPoint feet) {
        int x = localX(feet);
        int z = localZ(feet);
        return x >= 12 && x <= 16 && z >= 8 && z <= 12 && localY(feet) <= HOUSE_GROUND + 2;
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
