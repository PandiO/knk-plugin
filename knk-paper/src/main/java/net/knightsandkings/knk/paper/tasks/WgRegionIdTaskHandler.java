package net.knightsandkings.knk.paper.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.RemovalStrategy;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.knightsandkings.knk.core.domain.validation.ValidationResult;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.paper.regions.DomainRegionUnion;
import net.knightsandkings.knk.paper.regions.WorldGuardRegionRenamer;
import net.knightsandkings.knk.paper.regions.WorldGuardSubRegionMirrorStore;
import net.knightsandkings.knk.paper.tasks.validation.ParentRegionFeedback;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Handler for WgRegionId field tasks.
 * Allows players to define a WorldGuard region by either:
 * 1. Creating a new region using WorldEdit selection
 * 2. Selecting an existing WorldGuard region
 * Supports both approaches with validation and cleanup.
 */
public class WgRegionIdTaskHandler implements IWorldTaskHandler {
    private static final Logger LOGGER = Logger.getLogger(WgRegionIdTaskHandler.class.getName());
    private static final String FIELD_NAME = "WgRegionId";
    private static final String TEMP_REGION_PREFIX = "tempregion_worldtask_";
    
    // Custom flag to store creation timestamp
    public static final StringFlag CREATION_TIMESTAMP = new StringFlag("knk-creation-timestamp");

    private final WorldTasksApi worldTasksApi;
    private final Plugin plugin;
    
    // Track active tasks: player -> TaskContext
    private final Map<Player, TaskContext> activeTasksByPlayer = new HashMap<>();

    /**
     * Told when a temporary region became its final name, so the category's parent, priority and flags can be applied
     * to it straight away (the managed-region creation path). Null until the plugin wires it.
     */
    @FunctionalInterface
    public interface RegionFinalizer {
        /** @param world the region's world (KNG-112; null when it could not be told) */
        void finalized(String regionId, String domainType, String parentRegionId, String world);
    }

    private volatile RegionFinalizer regionFinalizer;

    public void setRegionFinalizer(RegionFinalizer regionFinalizer) {
        this.regionFinalizer = regionFinalizer;
    }

    /** KNG-255: told when a domain sub-region got its final name and was made a copy of its main region. */
    @FunctionalInterface
    public interface SubRegionFinalizer {
        void finalized(String subRegionId, String mainRegionId, String world);
    }

    private volatile SubRegionFinalizer subRegionFinalizer;

    public void setSubRegionFinalizer(SubRegionFinalizer subRegionFinalizer) {
        this.subRegionFinalizer = subRegionFinalizer;
    }

    /**
     * Internal context for tracking task state
     */
    private static class TaskContext {
        final int taskId;
        final String inputJson;
        String parentRegionId;
        int priority;
        Map<Flag<?>, Object> flags;
        String createdRegionId;
        boolean paused;

        TaskContext(int taskId, String inputJson) {
            this.taskId = taskId;
            this.inputJson = inputJson;
            this.priority = 0; // default priority
            this.flags = new HashMap<>();
            this.paused = false;
        }
    }

    public WgRegionIdTaskHandler(WorldTasksApi worldTasksApi, Plugin plugin) {
        this.worldTasksApi = worldTasksApi;
        this.plugin = plugin;
    }

    @Override
    public String getFieldName() {
        return FIELD_NAME;
    }

    @Override
    public void startTask(Player player, int taskId, String inputJson) {
        TaskContext context = new TaskContext(taskId, inputJson);
        
        // Parse input JSON for parent region, priority, flags if provided
        if (inputJson != null && !inputJson.isEmpty()) {
            try {
                JsonObject input = JsonParser.parseString(inputJson).getAsJsonObject();
                if (input.has("parentRegionId")) {
                    context.parentRegionId = input.get("parentRegionId").getAsString();
                }
                if (input.has("priority")) {
                    context.priority = input.get("priority").getAsInt();
                }
                // TODO: Parse flags from input if needed
            } catch (Exception e) {
                LOGGER.warning("Failed to parse input JSON for task " + taskId + ": " + e.getMessage());
            }
        }
        
        activeTasksByPlayer.put(player, context);
        
        // Enable CUI selection for WorldEdit (must run on main thread)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
                session.dispatchCUISelection(BukkitAdapter.adapt(player));
                
                player.sendMessage("§6[WorldTask] Define a WorldGuard region.");
                player.sendMessage("§7[WorldTask] Task ID: " + taskId);
                player.sendMessage("§eOption 1 - Create new region: Use '//sel poly' or '//sel cuboid', then type 'save'");
                player.sendMessage("§eOption 2 - Select existing: Type 'select {regionname}'");
                player.sendMessage("§7Or type 'cancel' to abort.");
                if (context.parentRegionId != null) {
                    player.sendMessage("§7Note: Region must be inside parent region: " + context.parentRegionId);
                }
                
                LOGGER.info("Started WgRegionId task for player " + player.getName() + " (task " + taskId + ")");
            } catch (Exception e) {
                player.sendMessage("§c[WorldTask] Failed to initialize WorldEdit session: " + e.getMessage());
                LOGGER.warning("Failed to initialize WorldEdit session for task " + taskId + ": " + e.getMessage());
            }
        });
    }

    @Override
    public boolean isHandling(Player player) {
        return activeTasksByPlayer.containsKey(player);
    }

    @Override
    public void cancel(Player player) {
        TaskContext context = activeTasksByPlayer.remove(player);
        if (context != null) {
            // Cleanup: remove temp region if created
            if (context.createdRegionId != null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    cleanupTempRegion(player.getWorld(), context.createdRegionId);
                });
            }
            
            // Clear WorldEdit session
            WorldEdit.getInstance().getSessionManager().remove(BukkitAdapter.adapt(player));
            
            player.sendMessage("§c[WorldTask] Task cancelled.");
            LOGGER.info("Cancelled WgRegionId task for player " + player.getName() + " (task " + context.taskId + ")");
        }
    }

    @Override
    public Integer getTaskId(Player player) {
        TaskContext context = activeTasksByPlayer.get(player);
        return context != null ? context.taskId : null;
    }

    /**
     * Handle chat input from player during task.
     * Processes 'save', 'cancel', 'pause', 'resume', 'select {regionname}' commands.
     * 
     * @param player The player
     * @param message The chat message
     * @return true if the message was handled and should be cancelled
     */
    public boolean onPlayerChat(Player player, String message) {
        TaskContext context = activeTasksByPlayer.get(player);
        if (context == null) return false;

        String cmd = message.trim().toLowerCase();
        
        if (cmd.equals("save")) {
            handleSave(player, context);
            return true;
        } else if (cmd.equals("cancel")) {
            cancel(player);
            return true;
        } else if (cmd.equals("pause") || cmd.equals("suspend")) {
            handlePause(player, context);
            return true;
        } else if (cmd.equals("resume")) {
            handleResume(player, context);
            return true;
        } else if (cmd.startsWith("select ")) {
            String regionName = message.trim().substring(7);
            handleSelect(player, context, regionName);
            return true;
        }
        
        return false;
    }

    /**
     * Handle save command: validate selection and create region
     */
    private void handleSave(Player player, TaskContext context) {
        if (context.paused) {
            player.sendMessage("§c[WorldTask] Task is paused. Type 'resume' to continue.");
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                World world = player.getWorld();
                
                // Get WorldEdit selection
                LocalSession session = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(player));
                Region selection = session.getSelection(BukkitAdapter.adapt(world));
                
                if (selection == null) {
                    player.sendMessage("§c[WorldTask] No WorldEdit selection found! Please select a region first.");
                    return;
                }

                // Validate region against validation rules from InputJson (KNG-173: a warning saves, a block refuses)
                player.sendMessage("§7Validating region selection...");
                ValidationResult validation = validateRegion(player, world, selectionPoints(selection),
                    net.knightsandkings.knk.paper.integration.WorldGuardIntegration.createRegionFromSelection(
                        selection, "knk_selection_check", 0),
                    "Your selection", context);
                if (!reportValidation(player, validation)) {
                    return; // Block save
                }

                // Validate parent region containment if required (legacy check)
                if (context.parentRegionId != null) {
                    RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                        .get(BukkitAdapter.adapt(world));

                    if (regionManager != null) {
                        ProtectedRegion parentRegion = regionManager.getRegion(context.parentRegionId);

                        if (parentRegion == null) {
                            player.sendMessage("§c[WorldTask] Parent region " + context.parentRegionId
                                + " doesn't exist in world " + world.getName() + ".");
                            return;
                        }
                        
                        if (!isSelectionInsideRegion(parentRegion, selection)) {
                            player.sendMessage("§c[WorldTask] Selection is not entirely inside required region: " + context.parentRegionId);
                            return;
                        }
                    }
                }

                // Create WorldGuard region from selection
                String tempRegionId = TEMP_REGION_PREFIX + context.taskId;
                ProtectedRegion region = net.knightsandkings.knk.paper.integration.WorldGuardIntegration.createRegionFromSelection(
                    selection, tempRegionId, context.priority);
                // KNG-257: say what a round selection became
                net.knightsandkings.knk.paper.integration.SelectionShapes.outlineOf(selection)
                    .map(net.knightsandkings.knk.paper.integration.SelectionShapes.Outline::note)
                    .ifPresent(note -> player.sendMessage("§7[WorldTask] " + note + "."));
                
                // Store creation timestamp for retention policy
                region.setFlag(CREATION_TIMESTAMP, String.valueOf(System.currentTimeMillis()));
                
                // Apply flags if any
                if (!context.flags.isEmpty()) {
                    for (Map.Entry<Flag<?>, Object> entry : context.flags.entrySet()) {
                        region.setFlag((Flag) entry.getKey(), entry.getValue());
                    }
                }

                // Register region in WorldGuard
                RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(world));
                
                if (regionManager != null) {
                    regionManager.addRegion(region);
                    context.createdRegionId = tempRegionId;
                    
                    // Clear WorldEdit session
                    WorldEdit.getInstance().getSessionManager().remove(BukkitAdapter.adapt(player));
                    
                    player.sendMessage("§a[WorldTask] Region created: " + tempRegionId);
                    player.sendMessage("§7Completing task...");
                    
                    // Complete task via API
                    completeTask(player, context, tempRegionId, world.getName());
                } else {
                    player.sendMessage("§c[WorldTask] Failed to access WorldGuard region manager.");
                }
                
            } catch (Exception e) {
                player.sendMessage("§c[WorldTask] Error creating region: " + e.getMessage());
                LOGGER.warning("Error in handleSave for task " + context.taskId + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Handle select command: validate and select an existing region
     */
    private void handleSelect(Player player, TaskContext context, String regionName) {
        if (context.paused) {
            player.sendMessage("§c[WorldTask] Task is paused. Type 'resume' to continue.");
            return;
        }

        if (regionName == null || regionName.trim().isEmpty()) {
            player.sendMessage("§c[WorldTask] Please specify a region name: select {regionname}");
            return;
        }

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                World world = player.getWorld();
                RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(world));
                
                if (regionManager == null) {
                    player.sendMessage("§c[WorldTask] Failed to access WorldGuard region manager.");
                    return;
                }
                
                ProtectedRegion region = regionManager.getRegion(regionName);
                
                if (region == null) {
                    player.sendMessage("§c[WorldTask] Region not found: " + regionName);
                    player.sendMessage("§7Available regions: " + String.join(", ", regionManager.getRegions().keySet()));
                    return;
                }

                // KNG-173: the task's validation rules apply to a selected region the same as to a drawn one
                player.sendMessage("§7Validating region " + regionName + "...");
                ValidationResult validation = validateRegion(player, world, regionPoints(region), region,
                    "Region " + regionName, context);
                if (!reportValidation(player, validation)) {
                    return;
                }

                // Validate parent region containment if required
                if (context.parentRegionId != null) {
                    ProtectedRegion parentRegion = regionManager.getRegion(context.parentRegionId);

                    if (parentRegion == null) {
                        player.sendMessage("§c[WorldTask] Parent region " + context.parentRegionId
                            + " doesn't exist in world " + world.getName() + ".");
                        return;
                    }
                    
                    // Check if selected region is completely inside parent region
                    if (!isRegionInsideRegion(parentRegion, region)) {
                        player.sendMessage("§c[WorldTask] Region is not entirely inside required parent region: " + context.parentRegionId);
                        return;
                    }
                }
                
                player.sendMessage("§a[WorldTask] Region selected: " + regionName);
                player.sendMessage("§7Completing task...");
                
                // Clear WorldEdit session
                WorldEdit.getInstance().getSessionManager().remove(BukkitAdapter.adapt(player));
                
                // Complete task via API
                completeTask(player, context, regionName, world.getName());
                
            } catch (Exception e) {
                player.sendMessage("§c[WorldTask] Error selecting region: " + e.getMessage());
                LOGGER.warning("Error in handleSelect for task " + context.taskId + ": " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    /**
     * Handle pause command
     */
    private void handlePause(Player player, TaskContext context) {
        context.paused = true;
        player.sendMessage("§e[WorldTask] Task paused. Your selection is preserved.");
        player.sendMessage("§7Type 'resume' to continue, or 'cancel' to abort.");
        LOGGER.info("Paused WgRegionId task " + context.taskId + " for player " + player.getName());
    }

    /**
     * Handle resume command
     */
    private void handleResume(Player player, TaskContext context) {
        if (!context.paused) {
            player.sendMessage("§c[WorldTask] Task is not paused.");
            return;
        }
        
        context.paused = false;
        player.sendMessage("§a[WorldTask] Task resumed.");
        player.sendMessage("§7Type 'save' to confirm your selection.");
        LOGGER.info("Resumed WgRegionId task " + context.taskId + " for player " + player.getName());
    }

    /**
     * Complete the task via API
     */
    private void completeTask(Player player, TaskContext context, String regionId, String worldName) {
        // Build output JSON
        JsonObject output = new JsonObject();
        output.addProperty("fieldName", FIELD_NAME);
        output.addProperty("regionId", regionId);
        output.addProperty("createdAt", System.currentTimeMillis());
        output.addProperty("worldName", worldName);
        
        if (context.parentRegionId != null) {
            output.addProperty("parentRegionId", context.parentRegionId);
        }
        
        String outputJson = output.toString();

        // Complete the task via API
        worldTasksApi.complete(context.taskId, outputJson)
            .thenAccept(completedTask -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    activeTasksByPlayer.remove(player);
                    player.sendMessage("§a[WorldTask] ✓ Task completed! Region " + regionId + " has been created.");
                    LOGGER.info("Completed WgRegionId task for player " + player.getName() 
                        + " (task " + context.taskId + ") with region: " + regionId);
                });
            })
            .exceptionally(ex -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§c[WorldTask] Failed to complete task: " + ex.getMessage());
                    player.sendMessage("§7The region was created but not saved. Cleaning up...");
                    cleanupTempRegion(player.getWorld(), regionId);
                    LOGGER.warning("Failed to complete WgRegionId task " + context.taskId + ": " + ex.getMessage());
                });
                return null;
            });
    }
    /**
     * Check if a WorldGuard region is completely inside another WorldGuard region.
     * Supports cuboid, polygon2d and polygon3d (polyhedral) regions.
     */
    private boolean isRegionInsideRegion(ProtectedRegion parentRegion, ProtectedRegion childRegion) {
        if (childRegion instanceof ProtectedPolygonalRegion) {
            ProtectedPolygonalRegion polygonRegion = (ProtectedPolygonalRegion) childRegion;
            return isPolygonal2DInsideRegion(parentRegion, polygonRegion.getPoints(),
                polygonRegion.getMinimumPoint().y(), polygonRegion.getMaximumPoint().y());
        }

        if (childRegion instanceof ProtectedCuboidRegion) {
            return isCuboidInsideRegion(parentRegion, (ProtectedCuboidRegion) childRegion);
        }

        if ("ProtectedPolyhedralRegion".equals(childRegion.getClass().getSimpleName())) {
            return isPolyhedralInsideRegionReflective(parentRegion, childRegion);
        }

        BlockVector3 min = childRegion.getMinimumPoint();
        BlockVector3 max = childRegion.getMaximumPoint();
        return parentRegion.contains(min) && parentRegion.contains(max);
    }

    private boolean isPolygonal2DInsideRegion(ProtectedRegion parentRegion, List<BlockVector2> points, int minY, int maxY) {
        for (BlockVector2 point : points) {
            BlockVector3 lower = point.toBlockVector3(minY);
            BlockVector3 upper = point.toBlockVector3(maxY);

            if (!parentRegion.contains(lower) || !parentRegion.contains(upper)) {
                return false;
            }
        }
        return true;
    }

    private boolean isCuboidInsideRegion(ProtectedRegion parentRegion, ProtectedCuboidRegion cuboidRegion) {
        BlockVector3 min = cuboidRegion.getMinimumPoint();
        BlockVector3 max = cuboidRegion.getMaximumPoint();

        int minX = min.x();
        int minY = min.y();
        int minZ = min.z();
        int maxX = max.x();
        int maxY = max.y();
        int maxZ = max.z();

        BlockVector3[] corners = new BlockVector3[] {
            BlockVector3.at(minX, minY, minZ),
            BlockVector3.at(minX, minY, maxZ),
            BlockVector3.at(minX, maxY, minZ),
            BlockVector3.at(minX, maxY, maxZ),
            BlockVector3.at(maxX, minY, minZ),
            BlockVector3.at(maxX, minY, maxZ),
            BlockVector3.at(maxX, maxY, minZ),
            BlockVector3.at(maxX, maxY, maxZ)
        };

        for (BlockVector3 corner : corners) {
            if (!parentRegion.contains(corner)) {
                return false;
            }
        }

        return true;
    }

    private boolean isPolyhedralInsideRegionReflective(ProtectedRegion parentRegion, ProtectedRegion polyhedralRegion) {
        try {
            Object pointsObject = polyhedralRegion.getClass().getMethod("getPoints").invoke(polyhedralRegion);
            if (!(pointsObject instanceof Iterable)) {
                return false;
            }

            for (Object point : (Iterable<?>) pointsObject) {
                if (point instanceof BlockVector3) {
                    if (!parentRegion.contains((BlockVector3) point)) {
                        return false;
                    }
                }
            }

            return true;
        } catch (Exception e) {
            LOGGER.warning("Failed polyhedral containment check: " + e.getMessage());
            return false;
        }
    }


    /**
     * Called when a player enters a region (legacy support).
     * If the player is handling a WgRegionId task, captures the region ID and completes the task.
     * 
     * @param player The player entering the region
     * @param regionId The WorldGuard region ID
     * @deprecated Use selection-based creation instead
     */
    @Deprecated
    public void onRegionEnter(Player player, String regionId) {
        // TaskContext context = activeTasksByPlayer.get(player);
        // if (context == null) return;

        // // Build output JSON
        // String outputJson = String.format(
        //     "{\"fieldName\":\"WgRegionId\",\"claimedRegionId\":\"%s\",\"claimedAt\":%d}",
        //     regionId.replace("\"", "\\\""),
        //     System.currentTimeMillis()
        // );

        // // Complete the task via API
        // worldTasksApi.complete(context.taskId, outputJson)
        //     .thenAccept(completedTask -> {
        //         plugin.getServer().getScheduler().runTask(plugin, () -> {
        //             activeTasksByPlayer.remove(player);
        //             player.sendMessage("§a[WorldTask] ✓ Task completed! Region " + regionId + " has been claimed.");
        //             LOGGER.info("Completed WgRegionId task for player " + player.getName() 
        //                 + " (task " + context.taskId + ") with region: " + regionId);
        //         });
        //     })
        //     .exceptionally(ex -> {
        //         plugin.getServer().getScheduler().runTask(plugin, () -> {
        //             player.sendMessage("§c[WorldTask] Failed to complete task: " + ex.getMessage());
        //             LOGGER.warning("Failed to complete WgRegionId task " + context.taskId + ": " + ex.getMessage());
        //         });
        //         return null;
        //     });
    }

    /**
     * Check if a WorldEdit selection is completely inside a WorldGuard region.
     * Validates all polygon vertices at min and max Y.
     */
    private boolean isSelectionInsideRegion(ProtectedRegion parentRegion, Region selection) {
        if (selection instanceof Polygonal2DRegion) {
            Polygonal2DRegion poly = (Polygonal2DRegion) selection;
            
            for (BlockVector2 v2 : poly.getPoints()) {
                BlockVector3 v3Min = v2.toBlockVector3(poly.getMinimumY());
                BlockVector3 v3Max = v2.toBlockVector3(poly.getMaximumY());
                
                if (!parentRegion.contains(v3Min) || !parentRegion.contains(v3Max)) {
                    return false;
                }
            }
            return true;
        }
        
        // For other selection types, check min/max points
        BlockVector3 min = selection.getMinimumPoint();
        BlockVector3 max = selection.getMaximumPoint();
        
        return parentRegion.contains(min) && parentRegion.contains(max);
    }

    /**
     * Rename a WorldGuard region from its old name to a new name.
     * This is used to finalize temporary region names when an entity is successfully created.
     * Format for domain instance entities: "domain_{entity-id}"
     * 
     * @param oldRegionId The current/temporary region ID
     * @param newRegionId The final region ID
     * @return true if successful, false otherwise
     */
    public boolean renameRegion(String oldRegionId, String newRegionId) {
        return renameRegion(oldRegionId, newRegionId, null, null);
    }

    /**
     * As {@link #renameRegion(String, String)}; when it succeeds and a {@link RegionFinalizer} is wired, also applies the
     * managed-region policy for a domain of {@code domainType} (null = unknown, left to the startup repair) whose parent
     * region is {@code parentRegionId}.
     */
    public boolean renameRegion(String oldRegionId, String newRegionId, String domainType, String parentRegionId) {
        return renameRegion(oldRegionId, newRegionId, domainType, parentRegionId, null);
    }

    /**
     * KNG-112: as {@link #renameRegion(String, String, String, String)}, renaming the region in {@code world} only (a
     * region id is unique per world). Null searches the loaded worlds, as before.
     */
    public boolean renameRegion(String oldRegionId, String newRegionId, String domainType, String parentRegionId,
                                String world) {
        return renameRegion(oldRegionId, newRegionId, domainType, parentRegionId, world, null);
    }

    /**
     * KNG-103: as {@link #renameRegion(String, String, String, String, String)}, replacing {@code replaceRegionId}, the
     * domain's region before its region step was run again (null: none). When that is the final name itself the existing
     * region is redefined in place with the new shape; a previous temp-named region is removed. See
     * {@link WorldGuardRegionRenamer} and {@link net.knightsandkings.knk.core.regions.RegionRenamePlan}.
     */
    public boolean renameRegion(String oldRegionId, String newRegionId, String domainType, String parentRegionId,
                                String world, String replaceRegionId) {
        return renameRegion(oldRegionId, newRegionId, domainType, parentRegionId, world, replaceRegionId, null);
    }

    /**
     * KNG-255: as above; with {@code subRegionOf} (the domain's main region) the renamed region is one of the domain's
     * sub-regions: instead of the category policy it becomes a copy of the main region at once (priority, parent = the
     * main region, flags, owners, members, {@link net.knightsandkings.knk.core.regions.SubRegionMirror}), and the
     * {@link SubRegionFinalizer} is told. The rename itself follows the same plan as a main region's (KNG-103 replace).
     */
    public boolean renameRegion(String oldRegionId, String newRegionId, String domainType, String parentRegionId,
                                String world, String replaceRegionId, String subRegionOf) {
        boolean renamed = renameRegionInternal(oldRegionId, newRegionId, world, replaceRegionId);
        if (renamed && subRegionOf != null && !subRegionOf.isBlank()) {
            finalizeSubRegion(newRegionId, subRegionOf.trim(), world);
            return true;
        }
        RegionFinalizer finalizer = regionFinalizer;
        if (renamed && finalizer != null && domainType != null && !domainType.isBlank()) {
            try {
                String finalWorld = world;
                if (finalWorld == null) {
                    World found = findWorldByRegion(newRegionId, null);
                    finalWorld = found != null ? found.getName() : null;
                }
                finalizer.finalized(newRegionId, domainType, parentRegionId, finalWorld);
            } catch (Exception e) {
                // The rename itself succeeded; the startup repair will still set the region up.
                LOGGER.warning("Managed-region setup of " + newRegionId + " failed: " + e.getMessage());
            }
        }
        return renamed;
    }

    private void finalizeSubRegion(String subRegionId, String mainRegionId, String worldName) {
        try {
            World world = findWorldByRegion(subRegionId, worldName);
            RegionManager manager = world == null ? null
                : WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
            ProtectedRegion sub = manager == null ? null : manager.getRegion(subRegionId);
            ProtectedRegion main = manager == null ? null : manager.getRegion(mainRegionId);
            if (sub == null || main == null) {
                // The rename succeeded; the next access sync sets the sub-region up once the API lists it.
                LOGGER.warning("Sub-region " + subRegionId + " renamed, but " + (sub == null ? "it" : "its main region " + mainRegionId)
                    + " is not in the same world; it is set up by the next region access sync");
                return;
            }
            WorldGuardSubRegionMirrorStore.apply(sub, main,
                net.knightsandkings.knk.core.regions.SubRegionMirror.desiredFor(main.getId(), WorldGuardSubRegionMirrorStore.stateOf(main)),
                WorldGuard.getInstance().getFlagRegistry());
            manager.saveChanges();
            LOGGER.info("Sub-region " + subRegionId + " set up as a copy of " + mainRegionId + " in " + world.getName());
            SubRegionFinalizer finalizer = subRegionFinalizer;
            if (finalizer != null) {
                finalizer.finalized(subRegionId, mainRegionId, world.getName());
            }
        } catch (Exception e) {
            // The rename itself succeeded; the next access sync makes it a copy of the main region.
            LOGGER.warning("Setting up sub-region " + subRegionId + " as a copy of " + mainRegionId + " failed: " + e.getMessage());
        }
    }

    private boolean renameRegionInternal(String oldRegionId, String newRegionId, String worldName, String replaceRegionId) {
        if (oldRegionId == null || oldRegionId.trim().isEmpty() || 
            newRegionId == null || newRegionId.trim().isEmpty()) {
            LOGGER.warning("Cannot rename region: oldRegionId or newRegionId is null/empty");
            return false;
        }
        
        if (oldRegionId.equals(newRegionId)) {
            // Already the desired name
            return true;
        }
        
        try {
            // Find the world containing this region (KNG-112: the named one only, when given)
            World world = findWorldByRegion(oldRegionId, worldName);
            if (world == null) {
                LOGGER.warning("Failed to rename region: could not find world containing region " + oldRegionId);
                return false;
            }
            
            RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(world));
            
            if (regionManager == null) {
                LOGGER.warning("Failed to rename region: could not access region manager for world " + world.getName());
                return false;
            }

            WorldGuardRegionRenamer.Result result =
                WorldGuardRegionRenamer.rename(regionManager, oldRegionId, newRegionId, replaceRegionId);
            if (!result.success()) {
                LOGGER.warning("Failed to rename region " + oldRegionId + " to " + newRegionId + " in " + world.getName()
                    + ": " + result.detail());
                return false;
            }
            LOGGER.info("Successfully renamed region from " + oldRegionId + " to " + newRegionId + " in " + world.getName()
                + ": " + result.detail());
            return true;
            
        } catch (Exception e) {
            LOGGER.warning("Failed to rename region from " + oldRegionId + " to " + newRegionId + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Check if a child region is fully contained within a parent region.
     * Used by the Web API to validate region containment rules.
     * 
     * @param parentRegionId The parent region ID
     * @param childRegionId The child region ID
     * @param requireFullContainment If true, all child region vertices must be inside parent
     * @return true if the child is contained within the parent (or if requireFullContainment is false), false otherwise
     */
    public boolean checkRegionContainment(String parentRegionId, String childRegionId, boolean requireFullContainment) {
        return checkRegionContainment(parentRegionId, childRegionId, requireFullContainment, null);
    }

    /** KNG-112: as {@link #checkRegionContainment(String, String, boolean)} in {@code world} only (null: any). */
    public boolean checkRegionContainment(String parentRegionId, String childRegionId, boolean requireFullContainment,
                                          String worldName) {
        if (parentRegionId == null || parentRegionId.trim().isEmpty() || 
            childRegionId == null || childRegionId.trim().isEmpty()) {
            LOGGER.warning("Cannot check region containment: parentRegionId or childRegionId is null/empty");
            return false;
        }

        try {
            // Find the world containing the parent region (KNG-112: the named one only, when given)
            World world = findWorldByRegion(parentRegionId, worldName);
            if (world == null) {
                LOGGER.warning("Cannot check region containment: parent region not found: " + parentRegionId);
                return false;
            }

            RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(world));

            if (regionManager == null) {
                LOGGER.warning("Cannot check region containment: region manager not available for world " + world.getName());
                return false;
            }

            ProtectedRegion parentRegion = regionManager.getRegion(parentRegionId);
            ProtectedRegion childRegion = regionManager.getRegion(childRegionId);

            if (parentRegion == null) {
                LOGGER.warning("Cannot check region containment: parent region not found: " + parentRegionId);
                return false;
            }

            if (childRegion == null) {
                LOGGER.warning("Cannot check region containment: child region not found: " + childRegionId);
                return false;
            }

            // If requireFullContainment is false, always return true (no validation)
            if (!requireFullContainment) {
                return true;
            }

            // Use shape-aware containment check logic; KNG-257: against the parent domain's every region
            List<ProtectedRegion> parentUnion = DomainRegionUnion.of(regionManager, parentRegion);
            boolean isContained = parentUnion.size() > 1
                ? DomainRegionUnion.outside(parentUnion, childRegion, regionPoints(childRegion), 1).isEmpty()
                : isRegionInsideRegion(parentRegion, childRegion);
            
            LOGGER.info("Region containment check: " + childRegionId + " inside " + parentRegionId + " = " + isContained);
            return isContained;

        } catch (Exception e) {
            LOGGER.warning("Error checking region containment: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Find the world that contains a specific region by name.
     * Searches all loaded worlds for the region.
     * 
     * @param regionId The region ID to search for
     * @return The world containing the region, or null if not found
     */
    private World findWorldByRegion(String regionId, String worldName) {
        try {
            if (worldName != null && !worldName.isBlank()) {
                // KNG-112: the same id can exist in several worlds; never fall through to another world's region.
                World named = plugin.getServer().getWorld(worldName.trim());
                if (named == null) {
                    LOGGER.warning("World " + worldName + " is not loaded (looking for region " + regionId + ")");
                    return null;
                }
                RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(named));
                return regionManager != null && regionManager.getRegion(regionId) != null ? named : null;
            }
            for (World world : plugin.getServer().getWorlds()) {
                RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(world));
                
                if (regionManager != null && regionManager.getRegion(regionId) != null) {
                    return world;
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Error searching for region " + regionId + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Cleanup temporary region if it's unused
     */
    private void cleanupTempRegion(World world, String regionId) {
        try {
            RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(world));
            
            if (regionManager != null && regionId.startsWith(TEMP_REGION_PREFIX)) {
                // TODO: Check if region is linked to any entity before removing
                // For now, we remove all temp regions on cleanup
                regionManager.removeRegion(regionId, RemovalStrategy.REMOVE_CHILDREN);
                LOGGER.info("Cleaned up temporary region: " + regionId);
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to cleanup temp region " + regionId + ": " + e.getMessage());
        }
    }

    private static final List<String> BLOCKED_ADVICE = List.of(
        "Region not saved. Please adjust your selection and type 'save' again, or 'select' another region.",
        "Or type 'cancel' to abort the task.",
        "If you believe this is an error, contact a developer.");

    /**
     * KNG-173: tells the player how the validation went. A block refuses; a warning is shown and the save goes on.
     *
     * @return true when the region may be saved
     */
    private static boolean reportValidation(Player player, ValidationResult validation) {
        for (String line : ParentRegionFeedback.chatLines(validation, BLOCKED_ADVICE,
                "Warning only - saving the region anyway.")) {
            player.sendMessage(line);
        }
        return validation.isValid() || !validation.isBlocking();
    }

    /**
     * Validate a region against the validation rules from InputJson. Every rule runs; a block from any rule wins
     * over warnings (KNG-173).
     *
     * @param subjectWorld the world the region is in
     * @param points       the region's corner points to check
     * @param what         what is checked, starting a sentence ("Your selection", "Region gate_1")
     * @param context      task context containing InputJson with validation rules
     * @return ValidationResult indicating success or failure
     */
    private ValidationResult validateRegion(Player player, World subjectWorld, List<BlockVector3> points,
                                            ProtectedRegion subject, String what, TaskContext context) {
        try {
            // Parse validation context from InputJson
            LOGGER.info("validateRegion called for task " + context.taskId);
            LOGGER.info("InputJson: " + context.inputJson);

            if (context.inputJson == null || context.inputJson.isEmpty()) {
                LOGGER.info("InputJson is empty - skipping validation");
                return ValidationResult.success("No validation configured");
            }

            JsonObject input = JsonParser.parseString(context.inputJson).getAsJsonObject();
            if (!input.has("validationContext") || !input.get("validationContext").isJsonObject()) {
                LOGGER.info("No validationContext in InputJson - skipping validation");
                return ValidationResult.success("No validation configured");
            }

            JsonObject validationContext = input.getAsJsonObject("validationContext");
            if (!validationContext.has("validationRules") || !validationContext.get("validationRules").isJsonArray()) {
                LOGGER.info("No validationRules in context - skipping validation");
                return ValidationResult.success("No validation rules");
            }

            JsonArray rules = validationContext.getAsJsonArray("validationRules");
            LOGGER.info("Found " + rules.size() + " validation rule(s)");

            List<ValidationResult> results = new ArrayList<>();
            for (JsonElement ruleElement : rules) {
                JsonObject rule = ruleElement.getAsJsonObject();
                String validationType = rule.get("validationType").getAsString();
                LOGGER.info("Processing validation rule: " + validationType);

                if ("RegionContainment".equals(validationType)) {
                    ValidationResult result = validateRegionContainment(player, subjectWorld, points, subject, what, rule);
                    if (!result.isValid()) {
                        LOGGER.info("Validation failed (blocking: " + result.isBlocking() + "): " + result.getMessage());
                    }
                    results.add(result);
                }
                // Add more validation types here as needed
            }

            return ParentRegionFeedback.combine(results);

        } catch (Exception e) {
            LOGGER.warning("Region validation error for task " + context.taskId + ": " + e.getMessage());
            e.printStackTrace();
            // Fail-open: Allow task to proceed if validation parsing fails
            return ValidationResult.success("Validation skipped due to error");
        }
    }

    /**
     * Validate that a region is fully contained within the region of the rule's dependency (e.g. the District).
     * Every failure follows the rule's isBlocking and names the dependency and its region (KNG-173).
     *
     * @param subjectWorld the world the region is in
     * @param points       the region's corner points
     * @param what         what is checked, starting a sentence
     * @param rule         the validation rule configuration
     * @return ValidationResult
     */
    private ValidationResult validateRegionContainment(Player player, World subjectWorld, List<BlockVector3> points,
                                                       ProtectedRegion subject, String what, JsonObject rule) {
        try {
            // Parse config
            JsonObject config = rule.has("configJson") && !rule.get("configJson").isJsonNull()
                ? JsonParser.parseString(rule.get("configJson").getAsString()).getAsJsonObject()
                : new JsonObject();

            // Log config for debugging
            LOGGER.info("Validating RegionContainment with config: " + config.toString());

            // Get pre-resolved placeholders from InputJson (if provided by frontend)
            JsonObject preResolvedPlaceholders = new JsonObject();
            if (rule.has("preResolvedPlaceholders") && rule.get("preResolvedPlaceholders").isJsonObject()) {
                preResolvedPlaceholders = rule.getAsJsonObject("preResolvedPlaceholders");
                LOGGER.info("Pre-resolved placeholders from frontend: " + preResolvedPlaceholders.toString());
            }

            // Log dependency path information for debugging multi-layer resolution
            String dependencyPath = rule.has("dependencyPath") && !rule.get("dependencyPath").isJsonNull()
                ? rule.get("dependencyPath").getAsString()
                : "(none)";
            LOGGER.info("Dependency path configured: " + dependencyPath);

            // Get parent region ID from dependency field value
            if (!rule.has("dependencyFieldValue") || rule.get("dependencyFieldValue").isJsonNull()) {
                LOGGER.info("No dependency value - validation skipped");
                return ValidationResult.success("No dependency value - validation skipped");
            }

            JsonElement depValue = rule.get("dependencyFieldValue");
            LOGGER.info("Dependency field value: " + depValue.toString());

            // Extract parent region ID from dependency (e.g., Town entity)
            String parentRegionId = extractParentRegionId(depValue, config);
            if (parentRegionId == null || parentRegionId.isEmpty()) {
                LOGGER.info("No parent region ID resolved - validation skipped");
                return ValidationResult.success("No parent region ID - validation skipped");
            }

            LOGGER.info("Resolved parent region ID: " + parentRegionId + " from dependency path: " + dependencyPath);
            ParentRegionFeedback parent = ParentRegionFeedback.of(rule, depValue, parentRegionId);

            // The parent region lives in the dependency's world when it names a loaded one, else the player's
            World parentWorld = resolveParentWorld(parent.worldName(), subjectWorld);
            RegionManager regionManager = WorldGuard.getInstance()
                .getPlatform()
                .getRegionContainer()
                .get(BukkitAdapter.adapt(parentWorld));

            if (regionManager == null) {
                return parent.noRegionData(parentWorld.getName());
            }

            ProtectedRegion parentRegion = regionManager.getRegion(parentRegionId);
            if (parentRegion == null) {
                // The check could not run, so the rule's "not inside" message would be wrong.
                ValidationResult missing = parent.parentRegionMissing(parentWorld.getName());
                LOGGER.info("RegionContainment validation failed: " + missing.getMessage());
                return missing;
            }

            if (!parentWorld.getName().equals(subjectWorld.getName())) {
                return parent.wrongWorld(what, subjectWorld.getName(), parentWorld.getName());
            }

            // Check the region's corner points against the parent domain's regions (KNG-257: its main region and its
            // sub-regions; with several, its columns are sampled too)
            StringBuilder violations = new StringBuilder();
            final int maxViolations = 5; // Limit violation output

            player.sendMessage("§7Checking against " + parent.describe() + "...");
            List<ProtectedRegion> parentUnion = DomainRegionUnion.of(regionManager, parentRegion);
            List<BlockVector3> outside = DomainRegionUnion.outside(parentUnion, subject, points, 1000);
            int violationCount = outside.size();
            for (BlockVector3 point : outside.subList(0, Math.min(maxViolations, outside.size()))) {
                if (violations.length() > 0) violations.append(", ");
                violations.append("(").append(point.x()).append(", ").append(point.y()).append(", ")
                    .append(point.z()).append(")");
            }

            LOGGER.info("Containment check complete. Points checked: " + points.size() + ", outside: " + violationCount);

            if (violationCount > 0) {
                if (violationCount > maxViolations) {
                    violations.append(String.format(" ... and %d more", violationCount - maxViolations));
                }
                // Plugin-computed values; they override the pre-resolved placeholders from the API
                JsonObject computedPlaceholders = new JsonObject();
                computedPlaceholders.addProperty("violationCount", String.valueOf(violationCount));
                computedPlaceholders.addProperty("violations", violations.toString());

                String errorMsg = rule.has("errorMessage") && !rule.get("errorMessage").isJsonNull()
                    ? parent.interpolate(rule.get("errorMessage").getAsString(), preResolvedPlaceholders,
                        computedPlaceholders)
                    : null;
                ValidationResult result = parent.notInside(errorMsg, "Corners outside it: " + violations);
                LOGGER.info("RegionContainment validation failed: " + violationCount
                    + " point(s) outside parent region. Blocking: " + result.isBlocking());
                return result;
            }

            // Success - interpolate success message if present
            String successMsg = "Region is fully contained";
            if (rule.has("successMessage") && !rule.get("successMessage").isJsonNull()) {
                successMsg = parent.interpolate(rule.get("successMessage").getAsString(), preResolvedPlaceholders,
                    null);
            }

            player.sendMessage("§a✓ " + successMsg);
            LOGGER.info("RegionContainment validation passed: " + successMsg);
            return ValidationResult.success(successMsg);

        } catch (Exception e) {
            LOGGER.warning("RegionContainment validation error: " + e.getMessage());
            e.printStackTrace();
            // Fail-open
            return ValidationResult.success("Validation skipped due to error");
        }
    }

    /** The dependency's world when it names one that is loaded, else {@code fallback} (KNG-173). */
    private World resolveParentWorld(String dependencyWorld, World fallback) {
        if (dependencyWorld != null) {
            World named = plugin.getServer().getWorld(dependencyWorld);
            if (named != null) {
                return named;
            }
            LOGGER.warning("World " + dependencyWorld + " named by the validation dependency is not loaded; "
                + "looking for the parent region in " + fallback.getName());
        }
        return fallback;
    }

    /**
     * The points of a WorldEdit selection checked against a parent region: a polygon's vertices at its lowest and
     * highest Y, a cuboid's eight corners, else the minimum and maximum point.
     */
    static List<BlockVector3> selectionPoints(Region selection) {
        // KNG-257: a cylinder, ellipsoid or convex selection is checked as the polygon it is stored as.
        var outline = net.knightsandkings.knk.paper.integration.SelectionShapes.outlineOf(selection);
        if (outline.isPresent()) {
            return polygonPoints(outline.get().points(), outline.get().minY(), outline.get().maxY());
        }
        if (selection instanceof CuboidRegion) {
            return cuboidCorners(selection.getMinimumPoint(), selection.getMaximumPoint());
        }
        return List.of(selection.getMinimumPoint(), selection.getMaximumPoint());
    }

    /** As {@link #selectionPoints(Region)} for an existing WorldGuard region. */
    static List<BlockVector3> regionPoints(ProtectedRegion region) {
        if (region instanceof ProtectedPolygonalRegion poly) {
            return polygonPoints(poly.getPoints(), poly.getMinimumPoint().y(), poly.getMaximumPoint().y());
        }
        if (region instanceof ProtectedCuboidRegion) {
            return cuboidCorners(region.getMinimumPoint(), region.getMaximumPoint());
        }
        if ("ProtectedPolyhedralRegion".equals(region.getClass().getSimpleName())) {
            try {
                Object pointsObject = region.getClass().getMethod("getPoints").invoke(region);
                if (pointsObject instanceof Iterable<?> iterable) {
                    List<BlockVector3> points = new ArrayList<>();
                    for (Object point : iterable) {
                        if (point instanceof BlockVector3 vector) {
                            points.add(vector);
                        }
                    }
                    if (!points.isEmpty()) {
                        return points;
                    }
                }
            } catch (Exception e) {
                LOGGER.warning("Could not read the points of polyhedral region " + region.getId() + ": " + e.getMessage());
            }
        }
        return List.of(region.getMinimumPoint(), region.getMaximumPoint());
    }

    private static List<BlockVector3> polygonPoints(List<BlockVector2> vertices, int minY, int maxY) {
        List<BlockVector3> points = new ArrayList<>();
        for (BlockVector2 vertex : vertices) {
            points.add(vertex.toBlockVector3(minY));
            points.add(vertex.toBlockVector3(maxY));
        }
        return new ArrayList<>(new LinkedHashSet<>(points));
    }

    private static List<BlockVector3> cuboidCorners(BlockVector3 min, BlockVector3 max) {
        List<BlockVector3> corners = new ArrayList<>();
        for (int x : new int[] {min.x(), max.x()}) {
            for (int y : new int[] {min.y(), max.y()}) {
                for (int z : new int[] {min.z(), max.z()}) {
                    corners.add(BlockVector3.at(x, y, z));
                }
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(corners)); // a flat cuboid has fewer distinct corners
    }

    /**
     * Extract parent region ID from dependency field value.
     * The dependency value could be a full entity object or just the region ID.
     * 
     * @param depValue The dependency field value from InputJson
     * @param config The validation rule config
     * @return The parent region ID, or null if not found
     */
    private String extractParentRegionId(JsonElement depValue, JsonObject config) {
        try {
            // Get the property path from config (e.g., "WgRegionId")
            String regionPath = config.has("parentRegionPath") 
                ? config.get("parentRegionPath").getAsString()
                : "WgRegionId";
            
            LOGGER.info("Extracting parent region ID using path: " + regionPath + " from dependency value type: " + depValue.getClass().getSimpleName());
            
            // If depValue is a JSON object (Town entity), extract the region property
            if (depValue.isJsonObject()) {
                JsonObject entity = depValue.getAsJsonObject();
                LOGGER.info("Dependency is JSON object with keys: " + entity.keySet().toString());
                
                // Try exact match (PascalCase, e.g., "WgRegionId")
                if (entity.has(regionPath)) {
                    JsonElement regionIdElement = entity.get(regionPath);
                    if (regionIdElement.isJsonPrimitive()) {
                        String regionId = regionIdElement.getAsString();
                        LOGGER.info("Found region ID \"" + regionId + "\" at path: " + regionPath);
                        return regionId;
                    }
                }
                
                // Try camelCase variant (e.g., "wgRegionId" from "WgRegionId")
                String camelPath = Character.toLowerCase(regionPath.charAt(0)) + regionPath.substring(1);
                if (entity.has(camelPath)) {
                    JsonElement regionIdElement = entity.get(camelPath);
                    if (regionIdElement.isJsonPrimitive()) {
                        String regionId = regionIdElement.getAsString();
                        LOGGER.info("Found region ID \"" + regionId + "\" at camelCase path: " + camelPath);
                        return regionId;
                    }
                }
                
                // Try all lowercase as fallback
                String lowerPath = regionPath.toLowerCase();
                if (entity.has(lowerPath)) {
                    JsonElement regionIdElement = entity.get(lowerPath);
                    if (regionIdElement.isJsonPrimitive()) {
                        String regionId = regionIdElement.getAsString();
                        LOGGER.info("Found region ID \"" + regionId + "\" at lowercase path: " + lowerPath);
                        return regionId;
                    }
                }
                
                LOGGER.warning("Could not find region property \"" + regionPath + "\", \"" + camelPath + "\", or \"" + lowerPath + "\" in dependency object");
            }
            
            // If depValue is just the region ID string
            if (depValue.isJsonPrimitive()) {
                String regionId = depValue.getAsString();
                LOGGER.info("Dependency is primitive value: \"" + regionId + "\"");
                return regionId;
            }
            
            LOGGER.warning("Could not extract parent region ID from dependency value");
            return null;
        } catch (Exception e) {
            LOGGER.warning("Error extracting parent region ID: " + e.getMessage());
            return null;
        }
    }
}

