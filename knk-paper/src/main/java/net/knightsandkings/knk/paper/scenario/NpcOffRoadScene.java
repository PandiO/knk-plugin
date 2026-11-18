package net.knightsandkings.knk.paper.scenario;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Stairs;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;

/**
 * The scenes of link C1 (PandiO/knk-workspace#700): an off-road start or goal that a straight line cut into hops does
 * not reach, on a 64 × 32 × 64 box with the ground at {@link #GROUND} (so a ravine can be dug below it). Each kind has
 * its fake road (a loop of floor points, walked there and back by {@code ScenarioNpcs}), its start and its goal.
 * <ol>
 *   <li>{@link Kind#DETOUR}: a walled courtyard (x 22..42, z 20..50) south of a road along z 6, its only gate in the
 *       far wall; the goal deep in the courtyard. The road's nearest point is its end at x 32; the straight line to
 *       the goal runs into the near wall.</li>
 *   <li>{@link Kind#UPSTAIRS}: a 7 × 7 tower with a spiral stair (stair blocks round a 3 × 3 pillar, two turns, 32
 *       steps) and the goal on the pillar's top, 33 blocks above the ground, inside a curtain wall whose gate is in
 *       the east wall, away from the road (as the keep's tower stands in the keep); the road ends 14 blocks north of
 *       the curtain wall.</li>
 *   <li>{@link Kind#RAVINE}: a 5-deep ravine across the scene with one bridge (x 39..41); the road runs along the north bank,
 *       over the bridge and on to the east. The NPC starts on the south bank west of the bridge: the road's nearest
 *       point lies across the ravine, the road points it can reach are on the bridge.</li>
 * </ol>
 * Coordinates are scene blocks; {@link #feet} and {@link #floor} give world positions.
 */
public final class NpcOffRoadScene {

    public enum Kind { DETOUR, UPSTAIRS, RAVINE }

    /** The box every kind needs. */
    public static final ScenarioScene.Box BOX = new ScenarioScene.Box(64, 48, 64);
    /** Scene y of the ground's floor blocks; feet at {@code GROUND + 1}. */
    public static final int GROUND = 6;
    /** The tower's pillar top (its floor block), the upstairs goal's floor. */
    public static final int TOWER_TOP = GROUND + 32;

    private final ScenarioScene scene;
    private final Kind kind;

    private NpcOffRoadScene(ScenarioScene scene, Kind kind) {
        this.scene = scene;
        this.kind = kind;
    }

    public static NpcOffRoadScene in(ScenarioScene scene, Kind kind) {
        return new NpcOffRoadScene(scene, kind);
    }

    public Kind kind() {
        return kind;
    }

    /** The world feet position of a scene block's top at floor {@code floorY} (block centre). */
    public NavPoint feet(int x, int floorY, int z) {
        return new NavPoint(scene.world().getName(), scene.worldX(x) + 0.5, scene.worldY(floorY + 1),
            scene.worldZ(z) + 0.5);
    }

    /** The world floor point of a scene block (block centre). */
    public NavPoint floor(int x, int floorY, int z) {
        return new NavPoint(scene.world().getName(), scene.worldX(x) + 0.5, scene.worldY(floorY), scene.worldZ(z) + 0.5);
    }

    /** Builds the kind's scene. */
    public void build() {
        fill(0, GROUND, 0, 63, GROUND, 63, Material.GRASS_BLOCK);
        switch (kind) {
            case DETOUR -> buildDetour();
            case UPSTAIRS -> buildTower();
            case RAVINE -> buildRavine();
            default -> throw new IllegalStateException();
        }
        for (int[] p : roadCells()) {
            scene.block(p[0], p[1], p[2]).setType(Material.DIRT_PATH, false);
        }
    }

    // ---- the courtyard: walls x 22..42, z 20..50, 4 high; the gate at x 32..33 in the south wall ----

    private void buildDetour() {
        wallRing(22, 20, 42, 50, GROUND + 1, GROUND + 4);
        fill(32, GROUND + 1, 50, 33, GROUND + 3, 50, Material.AIR);
    }

    // ---- the tower: walls x 40..46, z 28..34; door at x 40, z 29; pillar x 42..44, z 30..32 ----

    /** The spiral's cells, step 0 (on the ground, inside the door) to step 15. */
    static final int[][] RING = {
        {41, 29}, {41, 30}, {41, 31}, {41, 32}, {41, 33}, {42, 33}, {43, 33}, {44, 33},
        {45, 33}, {45, 32}, {45, 31}, {45, 30}, {45, 29}, {44, 29}, {43, 29}, {42, 29}};
    /** Steps: two turns of the ring. */
    static final int STEPS = 2 * 16;
    /** The ring cells the top floor covers (above them the second turn's steps up to 29 have their headroom). */
    static final int TOP_OVER = 13;

    private void buildTower() {
        // the curtain wall round the tower (x 30..56, z 20..50), its gate in the east wall at z 38..39, away from the
        // road (inside the walk's box: the capture margin is 16 blocks round the road's end and the goal)
        wallRing(30, 20, 56, 50, GROUND + 1, GROUND + 4);
        fill(56, GROUND + 1, 38, 56, GROUND + 3, 39, Material.AIR);
        wallRing(40, 28, 46, 34, GROUND + 1, TOWER_TOP + 2);
        fill(40, GROUND + 1, 29, 40, GROUND + 2, 29, Material.AIR); // the door
        fill(42, GROUND + 1, 30, 44, TOWER_TOP, 32, Material.STONE_BRICKS); // the pillar, its top the goal's floor
        for (int k = 1; k < STEPS; k++) {
            int[] c = RING[k % RING.length];
            int[] next = RING[(k + 1) % RING.length];
            BlockFace up = face(next[0] - c[0], next[1] - c[1]);
            scene.block(c[0], GROUND + k, c[1]).setBlockData(org.bukkit.Bukkit.createBlockData(
                Material.STONE_BRICK_STAIRS, data -> ((Stairs) data).setFacing(up)), false);
        }
        for (int k = 0; k < TOP_OVER; k++) {
            scene.block(RING[k][0], TOWER_TOP, RING[k][1]).setType(Material.STONE_BRICKS, false);
        }
    }

    private static BlockFace face(int dx, int dz) {
        if (dx > 0) {
            return BlockFace.EAST;
        }
        if (dx < 0) {
            return BlockFace.WEST;
        }
        return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    // ---- the ravine: z 16..22 across the scene, floor at y 1; the bridge at x 39..41 ----

    private void buildRavine() {
        fill(0, GROUND, 16, 63, GROUND, 22, Material.AIR);
        fill(0, 1, 16, 63, 1, 22, Material.STONE);
        fill(39, GROUND, 16, 41, GROUND, 22, Material.OAK_PLANKS);
    }

    /** Whether a world position lies in the ravine below the ground (a fall). */
    public boolean inTheRavine(NavPoint at) {
        int z = (int) Math.floor(at.z()) - scene.worldZ(0);
        return kind == Kind.RAVINE && z >= 16 && z <= 22 && at.y() < scene.worldY(GROUND);
    }

    // ---- roads, starts and goals ----

    /** The road's floor cells (scene x, y, z), drawn for a person watching. */
    private List<int[]> roadCells() {
        List<int[]> out = new ArrayList<>();
        List<int[]> corners = roadCorners();
        for (int i = 1; i < corners.size(); i++) {
            int[] a = corners.get(i - 1);
            int[] b = corners.get(i);
            int n = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
            for (int s = 0; s <= n; s++) {
                int x = a[0] + Integer.signum(b[0] - a[0]) * s;
                int z = a[1] + Integer.signum(b[1] - a[1]) * s;
                if (kind != Kind.RAVINE || z < 16 || z > 22) {
                    out.add(new int[] {x, GROUND, z});
                }
            }
        }
        return out;
    }

    private List<int[]> roadCorners() {
        return switch (kind) {
            case DETOUR -> List.of(new int[] {4, 6}, new int[] {32, 6});
            case UPSTAIRS -> List.of(new int[] {4, 6}, new int[] {43, 6});
            case RAVINE -> List.of(new int[] {20, 12}, new int[] {40, 12}, new int[] {40, 36}, new int[] {58, 36});
        };
    }

    /** The fake road: its corners as floor points, every block, there and back (a loop for {@code ScenarioNpcs}). */
    public List<NavPoint> loop() {
        List<NavPoint> there = new ArrayList<>();
        List<int[]> corners = roadCorners();
        for (int i = 1; i < corners.size(); i++) {
            int[] a = corners.get(i - 1);
            int[] b = corners.get(i);
            int n = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
            for (int s = i == 1 ? 0 : 1; s <= n; s++) {
                there.add(floor(a[0] + Integer.signum(b[0] - a[0]) * s, GROUND, a[1] + Integer.signum(b[1] - a[1]) * s));
            }
        }
        List<NavPoint> loop = new ArrayList<>(there);
        for (int i = there.size() - 2; i >= 0; i--) {
            loop.add(there.get(i));
        }
        return loop;
    }

    /** Where the NPC (the group's leader) starts: feet. */
    public NavPoint start() {
        return switch (kind) {
            case DETOUR -> feet(6, GROUND, 6);
            case UPSTAIRS -> feet(6, GROUND, 6);
            case RAVINE -> feet(6, GROUND, 26);
        };
    }

    /** The goal: feet. */
    public NavPoint goal() {
        return switch (kind) {
            case DETOUR -> feet(32, GROUND, 44);
            case UPSTAIRS -> feet(43, TOWER_TOP, 31);
            case RAVINE -> feet(56, GROUND, 36);
        };
    }

    /** Where followers start: behind the leader, two abreast (feet). */
    public NavPoint followerStart(int i) {
        NavPoint s = start();
        int row = 1 + i / 2;
        int side = i % 2;
        return switch (kind) {
            case RAVINE -> new NavPoint(s.world(), s.x() + side, s.y(), s.z() + row);
            default -> new NavPoint(s.world(), s.x() - row, s.y(), s.z() + side);
        };
    }

    // ---- helpers ----

    private void fill(int x0, int y0, int z0, int x1, int y1, int z1, Material material) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                    scene.block(x, y, z).setType(material, false);
                }
            }
        }
    }

    private void wallRing(int x0, int z0, int x1, int z1, int y0, int y1) {
        fill(x0, y0, z0, x1, y1, z0, Material.STONE_BRICKS);
        fill(x0, y0, z1, x1, y1, z1, Material.STONE_BRICKS);
        fill(x0, y0, z0, x0, y1, z1, Material.STONE_BRICKS);
        fill(x1, y0, z0, x1, y1, z1, Material.STONE_BRICKS);
    }
}
