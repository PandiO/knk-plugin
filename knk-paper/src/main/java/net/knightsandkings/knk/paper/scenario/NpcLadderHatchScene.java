package net.knightsandkings.knk.paper.scenario;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Ladder;
import org.bukkit.block.data.type.TrapDoor;

import net.knightsandkings.knk.core.navigation.shape.NavPoint;

/**
 * The scene of link H1 (PandiO/knk-workspace#779): a small tower whose only way onto the roof is a ladder up its
 * inside north wall and a roof hatch (an oak trapdoor) directly above the ladder, as players build a watch tower.
 * <ol>
 *   <li>The tower: walls x 20..26, z 20..26, from the ground to {@link #ROOF} − 1; a doorway (no door block) in the
 *       west wall at z 23; the roof at {@link #ROOF} over the whole tower with a parapet one block high round it.</li>
 *   <li>The ladder at x 23, z 21 (facing south, on the north wall) from the ground floor up to {@link #ROOF} − 1; the
 *       hatch at x 23, {@link #ROOF}, z 21, facing south like the ladder, so its open panel lies against the same wall
 *       (Minecraft's own rule for a trapdoor a player climbs through).</li>
 *   <li>A fake road along z 6 from x 4 to x 22, north of the tower; the NPC starts on the road's west end, the goal is
 *       on the roof: an off-road goal, so the leg to it is a walk search's leg (link C1).</li>
 * </ol>
 * Coordinates are scene blocks; {@link #feet} and {@link #floor} give world positions.
 */
public final class NpcLadderHatchScene {

    /** The box the scene needs. */
    public static final ScenarioScene.Box BOX = new ScenarioScene.Box(40, 24, 40);
    /** Scene y of the ground's floor blocks; feet at {@code GROUND + 1}. */
    public static final int GROUND = 4;
    /** Scene y of the roof's floor blocks (and the hatch). */
    public static final int ROOF = GROUND + 8;
    /** The ladder column and the hatch above it. */
    public static final int LADDER_X = 23;
    public static final int LADDER_Z = 21;

    private final ScenarioScene scene;

    private NpcLadderHatchScene(ScenarioScene scene) {
        this.scene = scene;
    }

    public static NpcLadderHatchScene in(ScenarioScene scene) {
        return new NpcLadderHatchScene(scene);
    }

    /** The world feet position on a scene floor block (block centre). */
    public NavPoint feet(int x, int floorY, int z) {
        return new NavPoint(scene.world().getName(), scene.worldX(x) + 0.5, scene.worldY(floorY + 1),
            scene.worldZ(z) + 0.5);
    }

    /** The world floor point of a scene block (block centre). */
    public NavPoint floor(int x, int floorY, int z) {
        return new NavPoint(scene.world().getName(), scene.worldX(x) + 0.5, scene.worldY(floorY), scene.worldZ(z) + 0.5);
    }

    /** Builds the scene with the hatch open or closed. */
    public void build(boolean hatchOpen) {
        fill(0, GROUND, 0, 39, GROUND, 39, Material.GRASS_BLOCK);
        wallRing(20, 20, 26, 26, GROUND + 1, ROOF - 1);
        fill(20, GROUND + 1, 23, 20, GROUND + 2, 23, Material.AIR); // the doorway
        fill(20, ROOF, 20, 26, ROOF, 26, Material.STONE_BRICKS);
        wallRing(20, 20, 26, 26, ROOF + 1, ROOF + 1); // the parapet
        for (int y = GROUND + 1; y < ROOF; y++) {
            scene.block(LADDER_X, y, LADDER_Z).setBlockData(org.bukkit.Bukkit.createBlockData(Material.LADDER,
                data -> ((Ladder) data).setFacing(BlockFace.SOUTH)), false);
        }
        setHatch(hatchOpen);
        for (int x = 4; x <= 22; x++) {
            scene.block(x, GROUND, 6).setType(Material.DIRT_PATH, false);
        }
    }

    /** Opens or closes the hatch (a person or another NPC between two trips). */
    public void setHatch(boolean open) {
        scene.block(LADDER_X, ROOF, LADDER_Z).setBlockData(org.bukkit.Bukkit.createBlockData(Material.OAK_TRAPDOOR,
            data -> {
                TrapDoor trapdoor = (TrapDoor) data;
                trapdoor.setFacing(BlockFace.SOUTH);
                trapdoor.setHalf(Bisected.Half.TOP);
                trapdoor.setOpen(open);
            }), false);
    }

    /** The hatch block. */
    public Block hatch() {
        return scene.block(LADDER_X, ROOF, LADDER_Z);
    }

    /** Whether the hatch is open now. */
    public boolean hatchOpen() {
        return hatch().getBlockData() instanceof TrapDoor trapdoor && trapdoor.isOpen();
    }

    /** Whether a world position stands on the roof (feet at the roof's top or higher, inside the tower). */
    public boolean onTheRoof(NavPoint at) {
        int x = (int) Math.floor(at.x()) - scene.worldX(0);
        int z = (int) Math.floor(at.z()) - scene.worldZ(0);
        return x >= 21 && x <= 25 && z >= 21 && z <= 25 && at.y() >= scene.worldY(ROOF + 1) - 0.01;
    }

    /** The fake road: every block of z 6 from x 4 to 22, there and back. */
    public List<NavPoint> loop() {
        List<NavPoint> there = new ArrayList<>();
        for (int x = 4; x <= 22; x++) {
            there.add(floor(x, GROUND, 6));
        }
        List<NavPoint> loop = new ArrayList<>(there);
        for (int i = there.size() - 2; i >= 0; i--) {
            loop.add(there.get(i));
        }
        return loop;
    }

    /** Where the NPC starts: feet on the road's west end. */
    public NavPoint start() {
        return feet(5, GROUND, 6);
    }

    /** The goal: feet on the roof, in the tower's south-east quarter (away from the hatch). */
    public NavPoint goal() {
        return feet(24, ROOF, 24);
    }

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
