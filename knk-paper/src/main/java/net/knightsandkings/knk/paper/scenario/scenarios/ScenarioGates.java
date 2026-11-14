package net.knightsandkings.knk.paper.scenario.scenarios;

import net.knightsandkings.knk.api.dto.GateBlockSnapshotDto;
import net.knightsandkings.knk.api.dto.GateDoorDto;
import net.knightsandkings.knk.api.dto.GateStructureDto;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.core.scenario.ScenarioAssertionError;
import net.knightsandkings.knk.paper.gates.GateDisplayManager;
import net.knightsandkings.knk.paper.scenario.ScenarioScene;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Fake gate data for scenarios: the {@link GateStructureDto} knk-web-api would send for a small
 * portcullis, fed through the plugin's own {@code GateLoaderAdapter} into the live
 * {@link GateManager}, so the real animation task, safety guard and spatial index handle it. Ids
 * are the scene's negative fake ids, so a write-back to a connected dev API matches no real door.
 */
public final class ScenarioGates {

    private ScenarioGates() {
    }

    /** A loaded test portcullis: its ids and the scene it belongs to. */
    public record Portcullis(ScenarioScene scene, int structureId, int doorId, Material material) {

        public CachedGateDoor door() {
            CachedGateDoor door = gates().getGate(doorId);
            if (door == null) {
                throw new ScenarioAssertionError("test door " + doorId + " is not in the gate cache");
            }
            return door;
        }

        public GateManager gates() {
            return scene.services().gates();
        }

        /** World cells of the closed (frame 0) or open (last frame) door, from the plugin's own geometry. */
        public List<Block> cells(boolean open) {
            List<Vector> positions = open ? gates().openFootprint(doorId) : gates().closedFootprint(doorId);
            List<Block> blocks = new ArrayList<>();
            for (Vector position : positions) {
                blocks.add(scene.world().getBlockAt(position.getBlockX(), position.getBlockY(), position.getBlockZ()));
            }
            return blocks;
        }

        /** Null when every cell holds the material ({@code filled}) or air (not {@code filled}); else the reason. */
        public String cellsProblem(boolean open, boolean filled) {
            List<Block> cells = cells(open);
            if (cells.isEmpty()) {
                return "the " + (open ? "open" : "closed") + " door has no cells";
            }
            int wrong = 0;
            String example = null;
            for (Block cell : cells) {
                boolean ok = filled ? cell.getType() == material : cell.getType().isAir();
                if (!ok) {
                    wrong++;
                    if (example == null) {
                        example = cell.getType() + " at " + cell.getX() + "," + cell.getY() + "," + cell.getZ();
                    }
                }
            }
            return wrong == 0 ? null : wrong + " of " + cells.size() + " " + (open ? "open" : "closed")
                + " door cells are not " + (filled ? material : "air") + " (e.g. " + example + ")";
        }

        /** Removes the door from the plugin's caches and displays again. */
        public void unload() {
            GateManager gates = scene.services().gateManager().get();
            if (gates != null) {
                gates.removeStructure(structureId);
                gates.removeGate(doorId);
            }
            GateDisplayManager displays = scene.services().gateDisplayManager();
            if (displays != null) {
                displays.removeDisplay(doorId);
            }
        }
    }

    /**
     * Builds and loads a vertical portcullis {@code width} wide and {@code height} tall, facing north,
     * with its lowest-left block at scene position ({@code x}, {@code y}, {@code z}). Places the door's
     * blocks in its current position ({@code open}: lifted by its height) and returns it.
     */
    public static Portcullis loadPortcullis(ScenarioScene scene, int x, int y, int z, int width, int height,
                                            Material material, boolean open, int animationTicks) {
        int structureId = scene.fakeId(0);
        int doorId = scene.fakeId(1);
        String world = scene.world().getName();
        int ax = scene.worldX(x);
        int ay = scene.worldY(y);
        int az = scene.worldZ(z);

        GateDoorDto door = new GateDoorDto();
        door.setId(doorId);
        door.setGateStructureId(structureId);
        door.setName(scene.uniqueName("door"));
        door.setGateType("SLIDING");
        door.setMotionType("VERTICAL");
        door.setGeometryDefinitionMode("PLANE_GRID");
        door.setFaceDirection("NORTH");
        door.setAnimationDurationTicks(animationTicks);
        door.setAnimationTickRate(1);
        door.setAnchorPoint(point(ax, ay, az, world));
        door.setReferencePoint1(point(ax + width - 1, ay, az, world));
        door.setReferencePoint2(point(ax, ay + height - 1, az, world));
        door.setGeometryWidth(width);
        door.setGeometryHeight(height);
        door.setGeometryDepth(1);
        door.setMotionDistanceBlocks(height);
        door.setIsActive(true);
        door.setIsDestroyed(false);
        door.setIsInvincible(true);
        door.setCanRespawn(true);
        door.setShowHealthDisplay(false);
        door.setOpenedState(open ? "OPEN" : "CLOSED");
        List<GateBlockSnapshotDto> snapshots = new ArrayList<>();
        String blockData = material.createBlockData().getAsString();
        int order = 0;
        for (int v = 0; v < height; v++) {
            for (int u = 0; u < width; u++) {
                snapshots.add(new GateBlockSnapshotDto(scene.fakeId(100 + order), doorId, u, v, 0, ax + u, ay + v, az,
                    material.getKey().toString(), blockData, null, order));
                order++;
            }
        }
        door.setBlockSnapshots(snapshots);

        GateStructureDto structure = new GateStructureDto();
        structure.setId(structureId);
        structure.setName(scene.uniqueName("gate"));
        structure.setGateDoors(List.of(door));

        Portcullis portcullis = new Portcullis(scene, structureId, doorId, material);
        List<Integer> loaded = scene.services().gateLoaderAdapter().loadAndCacheStructure(structure);
        if (!loaded.contains(doorId)) {
            throw new ScenarioAssertionError("the gate loader did not cache the test door (loaded: " + loaded + ")");
        }
        for (Block cell : portcullis.cells(open)) {
            cell.setType(material, false);
        }
        return portcullis;
    }

    private static String point(int x, int y, int z, String world) {
        return "{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + ",\"world\":\"" + world + "\"}";
    }

    /** Fills a horizontal rectangle of the scene (inclusive corners) with a material. */
    public static void floor(ScenarioScene scene, int x1, int z1, int x2, int z2, int y, Material material) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                scene.block(x, y, z).setType(material, false);
            }
        }
    }
}
