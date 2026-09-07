package net.knightsandkings.knk.paper.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.knightsandkings.knk.api.GateStructuresApi;
import net.knightsandkings.knk.api.dto.GateBlockSnapshotScanDto;
import net.knightsandkings.knk.api.dto.GateStructureDto;
import net.knightsandkings.knk.api.dto.WorldTaskDto;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.core.util.CoordinateParser;
import net.knightsandkings.knk.core.util.VectorMath;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Headless WorldTask handler that scans a gate's blocks into GateBlockSnapshot rows.
 * Supports both geometry modes: PLANE_GRID (deterministic box) and FLOOD_FILL (BFS from SeedBlocks).
 *
 * Known limitations of this first implementation:
 * - Tile entity contents (chest inventory, sign text, etc.) are not captured; only a warning is emitted.
 * - ScanMaterialWhitelist/ScanMaterialBlacklist are interpreted as a JSON array (or comma-separated
 *   list) of Bukkit material keys, e.g. ["minecraft:stone","oak_planks"]; numeric MinecraftMaterialRef
 *   IDs are not resolved.
 */
public class GateBlockScanTaskHandler implements IHeadlessWorldTaskHandler {
    private static final Logger LOGGER = Logger.getLogger(GateBlockScanTaskHandler.class.getName());
    private static final String TASK_TYPE = "GateBlockScan";
    private static final int BLOCKS_PER_TICK = 200;
    private static final int BLOCKS_PER_TICK_WHEN_LAGGING = 50;
    private static final double LAG_TPS_THRESHOLD = 15.0;
    private static final int DEFAULT_SCAN_MAX_BLOCKS = 500; // matches GateStructure.ScanMaxBlocks default
    private static final int DEFAULT_SCAN_MAX_RADIUS = 20; // matches GateStructure.ScanMaxRadius default
    private static final int ABSOLUTE_MAX_CELLS = 20000; // hard ceiling regardless of gate configuration

    private final GateStructuresApi gateStructuresApi;
    private final WorldTasksApi worldTasksApi;
    private final Plugin plugin;

    public GateBlockScanTaskHandler(GateStructuresApi gateStructuresApi, WorldTasksApi worldTasksApi, Plugin plugin) {
        this.gateStructuresApi = gateStructuresApi;
        this.worldTasksApi = worldTasksApi;
        this.plugin = plugin;
    }

    @Override
    public boolean supports(String taskType) {
        return TASK_TYPE.equals(taskType);
    }

    @Override
    public void execute(WorldTaskDto task, Runnable onFinished) {
        Integer gateStructureId = parseGateStructureId(task.inputJson());
        if (gateStructureId == null) {
            fail(task.id(), "InputJson did not contain a valid gateStructureId.", onFinished);
            return;
        }

        gateStructuresApi.getById(gateStructureId).whenComplete((gate, error) -> {
            if (error != null || gate == null) {
                String reason = error != null ? error.getMessage() : "gate not found";
                fail(task.id(), "Could not load gate " + gateStructureId + ": " + reason, onFinished);
                return;
            }

            // Bukkit world/block access must happen on the main thread.
            plugin.getServer().getScheduler().runTask(plugin, () -> startScan(task.id(), gate, onFinished));
        });
    }

    private void startScan(int taskId, GateStructureDto gate, Runnable onFinished) {
        String mode = gate.getGeometryDefinitionMode();
        if ("PLANE_GRID".equals(mode)) {
            startPlaneGridScan(taskId, gate, onFinished);
        } else if ("FLOOD_FILL".equals(mode)) {
            startFloodFillScan(taskId, gate, onFinished);
        } else {
            fail(taskId, "Gate '" + gate.getName() + "' has an unknown GeometryDefinitionMode: " + mode, onFinished);
        }
    }

    private void startPlaneGridScan(int taskId, GateStructureDto gate, Runnable onFinished) {
        List<ScanWing> wings = buildScanWings(gate);
        if (wings.isEmpty()) {
            fail(taskId, "Gate '" + gate.getName() + "' is missing anchor/reference points required for scanning.", onFinished);
            return;
        }

        long totalCells = 0;
        for (ScanWing wing : wings) {
            totalCells += (long) wing.width * wing.height * wing.depth;
        }

        String sizeError = checkScanSizeLimit(totalCells, gate.getScanMaxBlocks());
        if (sizeError != null) {
            fail(taskId, "Scan area for gate '" + gate.getName() + "' " + sizeError, onFinished);
            return;
        }

        List<int[]> cells = new ArrayList<>();
        for (int wingIndex = 0; wingIndex < wings.size(); wingIndex++) {
            ScanWing wing = wings.get(wingIndex);
            for (int i = 0; i < wing.width; i++) {
                for (int j = 0; j < wing.height; j++) {
                    for (int k = 0; k < wing.depth; k++) {
                        cells.add(new int[]{wingIndex, i, j, k});
                    }
                }
            }
        }

        boolean captureTileEntities = !"NONE".equals(gate.getTileEntityPolicy());
        new ChunkedScanRunnable(taskId, gate, wings, cells, captureTileEntities, onFinished)
            .runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * One PLANE_GRID box per wing. Most gates have a single wing; DOUBLE_DOORS scans
     * both LeftDoorSeedBlock and RightDoorSeedBlock as independent boxes with the same footprint.
     */
    private List<ScanWing> buildScanWings(GateStructureDto gate) {
        List<ScanWing> wings = new ArrayList<>();

        Vector anchor = CoordinateParser.parseCoordinate(gate.getAnchorPoint());
        Vector ref1 = CoordinateParser.parseCoordinate(gate.getReferencePoint1());
        Vector ref2 = CoordinateParser.parseCoordinate(gate.getReferencePoint2());
        String worldName = CoordinateParser.parseWorldName(gate.getAnchorPoint());

        if (anchor == null || ref1 == null || ref2 == null || worldName.isBlank()) {
            return wings;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return wings;
        }

        // Lattice step vectors (not unit-normalized): the shortest integer step along each
        // reference direction, so integer width/height/depth indices always land on the true
        // adjacent Minecraft block - including for diagonal gates, where a unit vector like
        // (0.7071,0,0.7071) would under-shoot the real (1,0,1) neighbor. See VectorMath.primitiveLatticeStep.
        Vector uStep = VectorMath.primitiveLatticeStep(ref1.clone().subtract(anchor));
        Vector vStep = VectorMath.primitiveLatticeStep(ref2.clone().subtract(anchor));
        Vector nStep = VectorMath.primitiveLatticeStep(uStep.clone().crossProduct(vStep));

        int width = Math.max(1, gate.getGeometryWidth() != null ? gate.getGeometryWidth() : 1);
        int height = Math.max(1, gate.getGeometryHeight() != null ? gate.getGeometryHeight() : 1);
        int depth = Math.max(1, gate.getGeometryDepth() != null ? gate.getGeometryDepth() : 1);

        String leftSeedJson = gate.getLeftDoorSeedBlock();
        String rightSeedJson = gate.getRightDoorSeedBlock();
        Vector leftSeed = CoordinateParser.parseCoordinate(leftSeedJson);
        Vector rightSeed = CoordinateParser.parseCoordinate(rightSeedJson);

        if ("DOUBLE_DOORS".equals(gate.getGateType()) && leftSeed != null && rightSeed != null) {
            wings.add(new ScanWing(world, leftSeed, uStep, vStep, nStep, width, height, depth));
            wings.add(new ScanWing(world, rightSeed, uStep, vStep, nStep, width, height, depth));
        } else {
            wings.add(new ScanWing(world, anchor, uStep, vStep, nStep, width, height, depth));
        }

        return wings;
    }

    private void startFloodFillScan(int taskId, GateStructureDto gate, Runnable onFinished) {
        String worldName = CoordinateParser.parseWorldName(gate.getAnchorPoint());
        List<Vector> seeds = CoordinateParser.parseCoordinates(gate.getSeedBlocks());
        if (seeds.isEmpty()) {
            Vector singleSeed = CoordinateParser.parseCoordinate(gate.getSeedBlocks());
            if (singleSeed != null) {
                seeds = List.of(singleSeed);
            }
        }

        if (worldName.isBlank() || seeds.isEmpty()) {
            fail(taskId, "Gate '" + gate.getName() + "' is missing SeedBlocks or an anchor world required for FLOOD_FILL scanning.", onFinished);
            return;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            fail(taskId, "World '" + worldName + "' for gate '" + gate.getName() + "' is not loaded.", onFinished);
            return;
        }

        Set<String> whitelist = parseMaterialSet(gate.getScanMaterialWhitelist());
        Set<String> blacklist = parseMaterialSet(gate.getScanMaterialBlacklist());
        int scanMaxBlocks = Math.min(
            gate.getScanMaxBlocks() != null && gate.getScanMaxBlocks() > 0 ? gate.getScanMaxBlocks() : DEFAULT_SCAN_MAX_BLOCKS,
            ABSOLUTE_MAX_CELLS
        );
        int scanMaxRadius = gate.getScanMaxRadius() != null && gate.getScanMaxRadius() > 0
            ? gate.getScanMaxRadius() : DEFAULT_SCAN_MAX_RADIUS;
        boolean planeConstraint = Boolean.TRUE.equals(gate.getScanPlaneConstraint());
        boolean captureTileEntities = !"NONE".equals(gate.getTileEntityPolicy());

        new FloodFillScanRunnable(taskId, gate, world, seeds.get(0), seeds, whitelist, blacklist,
            scanMaxBlocks, scanMaxRadius, planeConstraint, captureTileEntities, onFinished)
            .runTaskTimer(plugin, 1L, 1L);
    }

    /**
     * Parses ScanMaterialWhitelist/ScanMaterialBlacklist as a JSON array of material keys,
     * falling back to a comma-separated list. Bare keys ("stone") are normalized to "minecraft:stone".
     */
    static Set<String> parseMaterialSet(String raw) {
        Set<String> result = new HashSet<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }

        List<String> tokens = new ArrayList<>();
        try {
            com.google.gson.JsonElement parsed = JsonParser.parseString(raw);
            if (parsed.isJsonArray()) {
                for (com.google.gson.JsonElement element : parsed.getAsJsonArray()) {
                    tokens.add(element.getAsString());
                }
            }
        } catch (Exception ignored) {
            // fall through to comma-separated parsing
        }

        if (tokens.isEmpty()) {
            for (String token : raw.split(",")) {
                if (!token.isBlank()) {
                    tokens.add(token.trim());
                }
            }
        }

        for (String token : tokens) {
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                continue;
            }
            result.add(normalized.contains(":") ? normalized : "minecraft:" + normalized);
        }

        return result;
    }

    private void fail(int taskId, String message, Runnable onFinished) {
        LOGGER.warning("[GateBlockScan] Task " + taskId + " failed: " + message);
        worldTasksApi.fail(taskId, message).whenComplete((result, ex) -> onFinished.run());
    }

    private void complete(int taskId, String outputJson, Runnable onFinished) {
        worldTasksApi.complete(taskId, outputJson).whenComplete((result, ex) -> {
            if (ex != null) {
                LOGGER.warning("[GateBlockScan] Task " + taskId + " scan finished but complete() call failed: " + ex.getMessage());
            }
            onFinished.run();
        });
    }

    /**
     * Forces the given chunk to load (and generate if necessary) so its blocks can be scanned.
     * Runs synchronously on the main thread, which is safe here since callers already run tick-budgeted.
     */
    private boolean ensureChunkLoaded(World world, int chunkX, int chunkZ) {
        if (world.isChunkLoaded(chunkX, chunkZ)) {
            return true;
        }
        try {
            return world.getChunkAt(chunkX, chunkZ) != null;
        } catch (Exception e) {
            LOGGER.warning("[GateBlockScan] Failed to load chunk (" + chunkX + ", " + chunkZ + "): " + e.getMessage());
            return false;
        }
    }

    private Integer parseGateStructureId(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return null;
        }
        try {
            JsonObject obj = JsonParser.parseString(inputJson).getAsJsonObject();
            if (obj.has("gateStructureId") && !obj.get("gateStructureId").isJsonNull()) {
                return obj.get("gateStructureId").getAsInt();
            }
        } catch (Exception e) {
            LOGGER.warning("[GateBlockScan] Could not parse InputJson: " + e.getMessage());
        }
        return null;
    }

    /**
     * Pure helper (no Bukkit dependency) so the size cap can be unit tested directly.
     * @return an error message fragment if the scan is too large, or null if it's within limits
     */
    static String checkScanSizeLimit(long totalCells, Integer configuredScanMaxBlocks) {
        int configuredCap = configuredScanMaxBlocks != null && configuredScanMaxBlocks > 0
            ? configuredScanMaxBlocks : DEFAULT_SCAN_MAX_BLOCKS;
        int effectiveCap = Math.min(configuredCap, ABSOLUTE_MAX_CELLS);

        if (totalCells > effectiveCap) {
            return "is too large: " + totalCells + " blocks exceeds the limit of " + effectiveCap
                + ". Reduce GeometryWidth/Height/Depth or increase ScanMaxBlocks.";
        }
        return null;
    }

    private record ScanWing(World world, Vector anchor, Vector uStep, Vector vStep, Vector nStep,
                            int width, int height, int depth) {
    }

    /**
     * Position of one scanned cell, both in the gate's relative coordinate system (persisted as
     * BlockSnapshot.relativePosition) and in absolute world coordinates (where to actually read
     * the block from). Pure function of the wing's lattice steps - no Bukkit World/Block/Location
     * involved - so it's directly unit-testable without a live server.
     */
    record CellPosition(int relativeX, int relativeY, int relativeZ, int worldX, int worldY, int worldZ) {
    }

    /**
     * Computes one PLANE_GRID cell's relative and world position from a wing's anchor and lattice
     * step vectors. Because uStep/vStep/nStep are exact integer vectors (see
     * VectorMath.primitiveLatticeStep), the offset is always integer-valued, so rounding the
     * relative position and flooring the world position (via Vector.getBlockX/Y/Z) always agree -
     * unlike the old unit-vector stepping, where a diagonal axis produced fractional offsets and
     * the two roundings could disagree.
     */
    static CellPosition computeCellPosition(Vector anchor, Vector uStep, Vector vStep, Vector nStep,
                                            int i, int j, int k) {
        Vector offset = uStep.clone().multiply(i)
            .add(vStep.clone().multiply(j))
            .add(nStep.clone().multiply(k));

        int relativeX = (int) Math.round(offset.getX());
        int relativeY = (int) Math.round(offset.getY());
        int relativeZ = (int) Math.round(offset.getZ());

        Vector worldPos = anchor.clone().add(offset);
        return new CellPosition(relativeX, relativeY, relativeZ,
            worldPos.getBlockX(), worldPos.getBlockY(), worldPos.getBlockZ());
    }

    /**
     * Scans a bounded number of cells per tick to avoid blocking the server thread
     * on large gates, and backs off further when the server is already lagging.
     */
    private class ChunkedScanRunnable extends BukkitRunnable {
        private final int taskId;
        private final GateStructureDto gate;
        private final List<ScanWing> wings;
        private final List<int[]> cells;
        private final boolean captureTileEntities;
        private final Runnable onFinished;

        private final List<GateBlockSnapshotScanDto> snapshots = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private int cursor = 0;
        private int sortOrder = 0;
        private int skippedUnloadedChunks = 0;
        private boolean tileEntityWarningAdded = false;

        ChunkedScanRunnable(int taskId, GateStructureDto gate, List<ScanWing> wings, List<int[]> cells,
                            boolean captureTileEntities, Runnable onFinished) {
            this.taskId = taskId;
            this.gate = gate;
            this.wings = wings;
            this.cells = cells;
            this.captureTileEntities = captureTileEntities;
            this.onFinished = onFinished;
        }

        @Override
        public void run() {
            int budget = isServerLagging() ? BLOCKS_PER_TICK_WHEN_LAGGING : BLOCKS_PER_TICK;
            int processed = 0;

            while (cursor < cells.size() && processed < budget) {
                processCell(cells.get(cursor));
                cursor++;
                processed++;
            }

            if (cursor >= cells.size()) {
                finish();
                cancel();
            }
        }

        private void processCell(int[] cell) {
            ScanWing wing = wings.get(cell[0]);
            int i = cell[1];
            int j = cell[2];
            int k = cell[3];

            CellPosition pos = computeCellPosition(wing.anchor, wing.uStep, wing.vStep, wing.nStep, i, j, k);

            int relativeX = pos.relativeX();
            int relativeY = pos.relativeY();
            int relativeZ = pos.relativeZ();

            int worldX = pos.worldX();
            int worldY = pos.worldY();
            int worldZ = pos.worldZ();

            int chunkX = worldX >> 4;
            int chunkZ = worldZ >> 4;
            if (!ensureChunkLoaded(wing.world, chunkX, chunkZ)) {
                skippedUnloadedChunks++;
                return;
            }

            Block block = wing.world.getBlockAt(worldX, worldY, worldZ);
            Material material = block.getType();

            String tileEntityJson = "{}";
            if (captureTileEntities && block.getState() instanceof TileState && !tileEntityWarningAdded) {
                warnings.add("Tile entity contents (inventory/text/etc.) are not captured yet; only the block type was recorded.");
                tileEntityWarningAdded = true;
            }

            snapshots.add(new GateBlockSnapshotScanDto(
                relativeX, relativeY, relativeZ,
                worldX, worldY, worldZ,
                material.getKey().toString(),
                block.getBlockData().getAsString(),
                tileEntityJson,
                sortOrder++
            ));
        }

        private boolean isServerLagging() {
            try {
                return Bukkit.getTPS()[0] < LAG_TPS_THRESHOLD;
            } catch (Exception e) {
                return false;
            }
        }

        private void finish() {
            if (skippedUnloadedChunks > 0) {
                warnings.add(skippedUnloadedChunks + " cell(s) were skipped because their chunk was not loaded.");
            }

            String status = warnings.isEmpty() ? "Success" : "Warning";
            if (snapshots.isEmpty()) {
                status = "Failed";
                warnings.add("No blocks were captured for gate '" + gate.getName() + "'.");
            }

            String outputJson = buildOutputJson(status, snapshots, warnings, null);

            if ("Failed".equals(status)) {
                fail(taskId, String.join(" ", warnings), onFinished);
            } else {
                complete(taskId, outputJson, onFinished);
            }
        }
    }

    /**
     * BFS flood fill from one or more seed blocks, respecting whitelist/blacklist boundaries,
     * ScanMaxBlocks/ScanMaxRadius limits, and an optional single-plane constraint.
     * Processes a bounded number of cells per tick, same as {@link ChunkedScanRunnable}.
     */
    private class FloodFillScanRunnable extends BukkitRunnable {
        private final int taskId;
        private final GateStructureDto gate;
        private final World world;
        private final Vector origin;
        private final Set<String> whitelist;
        private final Set<String> blacklist;
        private final int scanMaxBlocks;
        private final int scanMaxRadiusSquared;
        private final boolean planeConstraint;
        private final boolean captureTileEntities;
        private final Runnable onFinished;

        private final Deque<Vector> frontier = new ArrayDeque<>();
        private final Set<Long> visited = new HashSet<>();
        private final List<GateBlockSnapshotScanDto> snapshots = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private int sortOrder = 0;
        private int skippedUnloadedChunks = 0;
        private boolean tileEntityWarningAdded = false;
        private boolean cappedByMaxBlocks = false;

        FloodFillScanRunnable(int taskId, GateStructureDto gate, World world, Vector origin, List<Vector> seeds,
                              Set<String> whitelist, Set<String> blacklist, int scanMaxBlocks, int scanMaxRadius,
                              boolean planeConstraint, boolean captureTileEntities, Runnable onFinished) {
            this.taskId = taskId;
            this.gate = gate;
            this.world = world;
            this.origin = origin;
            this.whitelist = whitelist;
            this.blacklist = blacklist;
            this.scanMaxBlocks = scanMaxBlocks;
            this.scanMaxRadiusSquared = scanMaxRadius * scanMaxRadius;
            this.planeConstraint = planeConstraint;
            this.captureTileEntities = captureTileEntities;
            this.onFinished = onFinished;

            for (Vector seed : seeds) {
                Vector blockSeed = new Vector(seed.getBlockX(), seed.getBlockY(), seed.getBlockZ());
                if (visited.add(packCoordinate(blockSeed))) {
                    frontier.add(blockSeed);
                }
            }
        }

        @Override
        public void run() {
            int budget = isServerLagging() ? BLOCKS_PER_TICK_WHEN_LAGGING : BLOCKS_PER_TICK;
            int processed = 0;

            while (!frontier.isEmpty() && processed < budget && snapshots.size() < scanMaxBlocks) {
                processCell(frontier.poll());
                processed++;
            }

            if (snapshots.size() >= scanMaxBlocks && !frontier.isEmpty()) {
                cappedByMaxBlocks = true;
                frontier.clear();
            }

            if (frontier.isEmpty()) {
                finish();
                cancel();
            }
        }

        private void processCell(Vector cell) {
            if (origin.distanceSquared(cell) > scanMaxRadiusSquared) {
                return;
            }

            int worldX = cell.getBlockX();
            int worldY = cell.getBlockY();
            int worldZ = cell.getBlockZ();

            if (!ensureChunkLoaded(world, worldX >> 4, worldZ >> 4)) {
                skippedUnloadedChunks++;
                return;
            }

            Block block = world.getBlockAt(worldX, worldY, worldZ);
            String materialKey = block.getType().getKey().toString();

            if (!blacklist.isEmpty() && blacklist.contains(materialKey)) {
                return; // boundary: stop here, don't record, don't expand
            }
            if (!whitelist.isEmpty() && !whitelist.contains(materialKey)) {
                return; // boundary: not part of the gate, don't expand past it
            }

            int relativeX = worldX - origin.getBlockX();
            int relativeY = worldY - origin.getBlockY();
            int relativeZ = worldZ - origin.getBlockZ();

            String tileEntityJson = "{}";
            if (captureTileEntities && block.getState() instanceof TileState && !tileEntityWarningAdded) {
                warnings.add("Tile entity contents (inventory/text/etc.) are not captured yet; only the block type was recorded.");
                tileEntityWarningAdded = true;
            }

            snapshots.add(new GateBlockSnapshotScanDto(
                relativeX, relativeY, relativeZ,
                worldX, worldY, worldZ,
                materialKey,
                block.getBlockData().getAsString(),
                tileEntityJson,
                sortOrder++
            ));

            enqueueNeighbor(worldX + 1, worldY, worldZ);
            enqueueNeighbor(worldX - 1, worldY, worldZ);
            enqueueNeighbor(worldX, worldY, worldZ + 1);
            enqueueNeighbor(worldX, worldY, worldZ - 1);
            if (!planeConstraint) {
                enqueueNeighbor(worldX, worldY + 1, worldZ);
                enqueueNeighbor(worldX, worldY - 1, worldZ);
            }
        }

        private void enqueueNeighbor(int x, int y, int z) {
            Vector neighbor = new Vector(x, y, z);
            if (visited.add(packCoordinate(neighbor))) {
                frontier.add(neighbor);
            }
        }

        private long packCoordinate(Vector v) {
            return (((long) v.getBlockX() & 0x1FFFFF) << 42)
                | (((long) v.getBlockY() & 0xFFFFF) << 21)
                | ((long) v.getBlockZ() & 0x1FFFFF);
        }

        private boolean isServerLagging() {
            try {
                return Bukkit.getTPS()[0] < LAG_TPS_THRESHOLD;
            } catch (Exception e) {
                return false;
            }
        }

        private void finish() {
            if (skippedUnloadedChunks > 0) {
                warnings.add(skippedUnloadedChunks + " cell(s) were skipped because their chunk was not loaded.");
            }
            if (cappedByMaxBlocks) {
                warnings.add("Reached ScanMaxBlocks limit of " + scanMaxBlocks + "; the scan may be incomplete.");
            }

            String status = warnings.isEmpty() ? "Success" : "Warning";
            if (snapshots.isEmpty()) {
                status = "Failed";
                warnings.add("No blocks were captured for gate '" + gate.getName() + "'.");
            }

            String outputJson = buildOutputJson(status, snapshots, warnings, null);

            if ("Failed".equals(status)) {
                fail(taskId, String.join(" ", warnings), onFinished);
            } else {
                complete(taskId, outputJson, onFinished);
            }
        }
    }

    private String buildOutputJson(String status, List<GateBlockSnapshotScanDto> snapshots, List<String> warnings, String errorMessage) {
        JsonObject root = new JsonObject();
        root.addProperty("status", status);
        root.addProperty("blockCount", snapshots.size());

        JsonArray snapshotArray = new JsonArray();
        for (GateBlockSnapshotScanDto snapshot : snapshots) {
            JsonObject obj = new JsonObject();
            obj.addProperty("relativeX", snapshot.relativeX());
            obj.addProperty("relativeY", snapshot.relativeY());
            obj.addProperty("relativeZ", snapshot.relativeZ());
            obj.addProperty("worldX", snapshot.worldX());
            obj.addProperty("worldY", snapshot.worldY());
            obj.addProperty("worldZ", snapshot.worldZ());
            obj.addProperty("materialName", snapshot.materialName());
            obj.addProperty("blockDataJson", snapshot.blockDataJson());
            obj.addProperty("tileEntityJson", snapshot.tileEntityJson());
            obj.addProperty("sortOrder", snapshot.sortOrder());
            snapshotArray.add(obj);
        }
        root.add("snapshots", snapshotArray);

        JsonArray warningsArray = new JsonArray();
        for (String warning : warnings) {
            warningsArray.add(warning);
        }
        root.add("warnings", warningsArray);

        if (errorMessage != null) {
            root.addProperty("errorMessage", errorMessage);
        }

        return root.toString();
    }
}
