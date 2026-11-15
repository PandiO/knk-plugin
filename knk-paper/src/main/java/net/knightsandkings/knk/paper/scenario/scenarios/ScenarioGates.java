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
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pig;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
        return loadPortcullis(scene, 0, x, y, z, width, height, 1, material, open, animationTicks);
    }

    /**
     * As {@link #loadPortcullis(ScenarioScene, int, int, int, int, int, Material, boolean, int)}, {@code depth} blocks
     * deep (its layers at {@code z} to {@code z + depth - 1}). {@code slot} (0 to 3) picks the door's fake ids, so a
     * scene can load several doors.
     */
    public static Portcullis loadPortcullis(ScenarioScene scene, int slot, int x, int y, int z, int width, int height,
                                            int depth, Material material, boolean open, int animationTicks) {
        if (slot < 0 || slot > 3) {
            throw new IllegalArgumentException("slot must be 0 to 3, was " + slot);
        }
        int structureId = scene.fakeId(slot * 2);
        int doorId = scene.fakeId(slot * 2 + 1);
        String world = scene.world().getName();
        int ax = scene.worldX(x);
        int ay = scene.worldY(y);
        int az = scene.worldZ(z);

        GateDoorDto door = new GateDoorDto();
        door.setId(doorId);
        door.setGateStructureId(structureId);
        door.setName(scene.uniqueName(slot == 0 ? "door" : "door" + slot));
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
        door.setGeometryDepth(depth);
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
        for (int w = 0; w < depth; w++) {
            for (int v = 0; v < height; v++) {
                for (int u = 0; u < width; u++) {
                    snapshots.add(new GateBlockSnapshotDto(scene.fakeId(100 + slot * 200 + order), doorId, u, v, w, ax + u,
                        ay + v, az + w, material.getKey().toString(), blockData, null, order));
                    order++;
                }
            }
        }
        door.setBlockSnapshots(snapshots);

        GateStructureDto structure = new GateStructureDto();
        structure.setId(structureId);
        structure.setName(scene.uniqueName(slot == 0 ? "gate" : "gate" + slot));
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

    /** A pig that stands still and quiet at a scene position (a mob standing in for a body; removed with the scene). */
    public static Pig stillPig(ScenarioScene scene, double x, double y, double z) {
        Pig pig = scene.spawn(x, y, z, Pig.class);
        pig.setAI(false);
        pig.setSilent(true);
        return pig;
    }

    /** Whether the entity's box overlaps any of the cells. */
    public static boolean overlaps(Entity entity, List<Block> cells) {
        BoundingBox box = entity.getBoundingBox();
        for (Block cell : cells) {
            if (box.overlaps(BoundingBox.of(cell))) {
                return true;
            }
        }
        return false;
    }

    /** Null when the mob is alive and at full health; else what is wrong. */
    public static String unharmedProblem(LivingEntity mob) {
        if (!mob.isValid() || mob.isDead()) {
            return "the " + mob.getType() + " is gone or dead";
        }
        var attribute = mob.getAttribute(Attribute.MAX_HEALTH);
        double max = attribute == null ? mob.getHealth() : attribute.getValue();
        return mob.getHealth() >= max ? null : "the " + mob.getType() + " has " + mob.getHealth() + " of " + max + " health";
    }

    /**
     * Counts the suffocation damage events of the entities, cancelled ones included, while the scene is open. A mob
     * a closing door moved out in time never gets one; one means the door caught it and only the fallback guard
     * ({@code GateSuffocationGuard}) got it out.
     */
    public static AtomicInteger countSuffocation(ScenarioScene scene, Entity... entities) {
        AtomicInteger count = new AtomicInteger();
        List<Entity> watched = List.of(entities);
        scene.onEvent(EntityDamageEvent.class, event -> {
            if (event.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION && watched.contains(event.getEntity())) {
                count.incrementAndGet();
            }
        });
        return count;
    }
}
