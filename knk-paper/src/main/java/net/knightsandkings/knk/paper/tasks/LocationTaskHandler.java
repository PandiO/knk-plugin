package net.knightsandkings.knk.paper.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.knightsandkings.knk.core.domain.validation.ValidationResult;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.paper.utils.PlaceholderInterpolationUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Handler for Location field tasks.
 * Allows admins to capture a player's current position by typing 'save', or a
 * block location by right-clicking a block during the task.
 */
public class LocationTaskHandler implements IWorldTaskHandler {
    private static final Logger LOGGER = Logger.getLogger(LocationTaskHandler.class.getName());
    private static final String FIELD_NAME = "Location";

    private final WorldTasksApi worldTasksApi;
    private final Plugin plugin;
    
    // Track active tasks: player -> TaskContext
    private final Map<Player, TaskContext> activeTasksByPlayer = new HashMap<>();

    /**
     * Internal context for tracking task state
     */
    private static class TaskContext {
        final int taskId;
        final String inputJson;
        boolean paused;
        Location capturedLocation;  // Store for potential retry validation

        TaskContext(int taskId, String inputJson) {
            this.taskId = taskId;
            this.inputJson = inputJson;
            this.paused = false;
            this.capturedLocation = null;
        }
    }

    public LocationTaskHandler(WorldTasksApi worldTasksApi, Plugin plugin) {
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
        activeTasksByPlayer.put(player, context);

        player.sendMessage("§6[WorldTask] Capture your current location.");
        player.sendMessage("§7[WorldTask] Task ID: " + taskId);
        player.sendMessage("§eType 'save' to capture your current position (x, y, z, yaw, pitch)");
        player.sendMessage("§eType 'save <name>' to capture and set a custom location name (supports spaces)");
        player.sendMessage("§eOr right-click a block to capture that block's location");
        player.sendMessage("§eType 'pause' to build and interact with blocks normally");
        player.sendMessage("§eType 'resume' to continue after pausing");
        player.sendMessage("§7Or type 'cancel' to abort.");

        LOGGER.info("Started Location task for player " + player.getName() + " (task " + taskId + ")");
    }

    @Override
    public boolean isHandling(Player player) {
        return activeTasksByPlayer.containsKey(player);
    }

    @Override
    public void cancel(Player player) {
        TaskContext context = activeTasksByPlayer.remove(player);
        if (context != null) {
            player.sendMessage("§c[WorldTask] Task cancelled.");
            LOGGER.info("Cancelled Location task for player " + player.getName() + " (task " + context.taskId + ")");
        }
    }

    @Override
    public Integer getTaskId(Player player) {
        TaskContext context = activeTasksByPlayer.get(player);
        return context != null ? context.taskId : null;
    }

    /**
     * Handle chat input from player during task.
     * Processes 'save', 'cancel', 'pause', 'resume' commands.
     * 
     * @param player The player
     * @param message The chat message
     * @return true if the message was handled and should be cancelled
     */
    public boolean onPlayerChat(Player player, String message) {
        TaskContext context = activeTasksByPlayer.get(player);
        if (context == null) return false;

        String trimmedMessage = message == null ? "" : message.trim();
        if (trimmedMessage.isEmpty()) {
            return false;
        }

        String[] parts = trimmedMessage.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        
        if (cmd.equals("save")) {
            String locationName = parts.length > 1 ? parts[1].trim() : null;
            handleSave(player, context, locationName);
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
        }
        
        return false;
    }

    /**
     * Handle save command: capture player location and complete task
     */
    private void handleSave(Player player, TaskContext context, String providedLocationName) {
        captureLocation(player, context, player.getLocation(), providedLocationName);
    }

    /**
     * Handle a block right-click during an active location task.
     *
     * @return true if the block selection was handled
     */
    public boolean onBlockRightClick(Player player, Block clickedBlock) {
        TaskContext context = activeTasksByPlayer.get(player);
        if (context == null || context.paused || clickedBlock == null) {
            return false;
        }

        captureLocation(player, context, clickedBlock.getLocation(), null);
        return true;
    }

    private void captureLocation(Player player, TaskContext context, Location location, String providedLocationName) {
        if (context.paused) {
            player.sendMessage("§c[WorldTask] Task is paused. Type 'resume' to continue.");
            return;
        }

        try {
            // Validate location
            if (location == null || location.getWorld() == null) {
                player.sendMessage("§c[WorldTask] Error: Invalid location. Please try again.");
                LOGGER.warning("Invalid location captured for task " + context.taskId);
                return;
            }
            
            // Capture position and rotation
            double x = location.getX();
            double y = location.getY();
            double z = location.getZ();
            float yaw = location.getYaw();
            float pitch = location.getPitch();
            String worldName = location.getWorld().getName();
            String locationName = resolveLocationName(providedLocationName);
            
            // Store for validation if needed
            context.capturedLocation = location;
            
            player.sendMessage("§a[WorldTask] Location captured!");
            player.sendMessage(String.format("§7Position: (%.2f, %.2f, %.2f)", x, y, z));
            player.sendMessage(String.format("§7Rotation: yaw=%.2f, pitch=%.2f", yaw, pitch));
            player.sendMessage("§7World: " + worldName);
            player.sendMessage("§7Name: " + locationName);
            
            // NEW: Validate against rules from InputJson
            player.sendMessage("§7Validating location...");
            ValidationResult validation = validateLocation(player, location, context);
            if (!validation.isValid()) {
                player.sendMessage("§c✗ Validation Failed:");
                player.sendMessage("§c" + validation.getMessage());
                player.sendMessage("§e");
                if (validation.isBlocking()) {
                    player.sendMessage("§eTask not completed. Please move to a valid location and type 'save' again.");
                    player.sendMessage("§eOr type 'cancel' to abort the task.");
                    player.sendMessage("§eIf you believe this is an error, contact a developer.");
                    return; // Block completion
                } else {
                    player.sendMessage("§eWarning only - proceeding with save.");
                }
            }
            
            player.sendMessage("§7Completing task...");
            
            // Complete task via API
            completeTask(player, context, x, y, z, yaw, pitch, worldName, locationName);
            
        } catch (Exception e) {
            player.sendMessage("§c[WorldTask] Error capturing location: " + e.getMessage());
            LOGGER.warning("Error in handleSave for task " + context.taskId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle pause command
     */
    private void handlePause(Player player, TaskContext context) {
        context.paused = true;
        player.sendMessage("§e[WorldTask] Task paused.");
        player.sendMessage("§7You can now place and interact with blocks normally.");
        player.sendMessage("§7Type 'resume' to continue, or 'cancel' to abort.");
        LOGGER.info("Paused Location task " + context.taskId + " for player " + player.getName());
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
        player.sendMessage("§7Type 'save' to capture your current position, or right-click a block.");
        LOGGER.info("Resumed Location task " + context.taskId + " for player " + player.getName());
    }

    /**
     * Complete the task via API.
     * First creates a Location entity via API, then completes the task with the location ID.
     */
    private void completeTask(Player player, TaskContext context, double x, double y, double z, 
                              float yaw, float pitch, String worldName, String locationName) {
        // Step 1: Create Location entity via API
        createLocationAndCompleteTask(player, context, x, y, z, yaw, pitch, worldName, locationName);
    }

    /**
     * Create Location entity via API and complete the task.
     * The Location API must return the created location with its ID.
     */
    private void createLocationAndCompleteTask(Player player, TaskContext context, double x, double y, double z,
                                                float yaw, float pitch, String worldName, String locationName) {
        // Build Location DTO for API creation
        JsonObject locationDto = new JsonObject();
        locationDto.addProperty("name", locationName);
        locationDto.addProperty("x", x);
        locationDto.addProperty("y", y);
        locationDto.addProperty("z", z);
        locationDto.addProperty("yaw", yaw);
        locationDto.addProperty("pitch", pitch);
        locationDto.addProperty("world", worldName);

        // Call API to create Location (assuming worldTasksApi has a location creation method)
        // For now, we'll complete the task with the raw data and let the backend handle it
        // This is a fallback if the plugin doesn't have direct Location API access
        completeTaskWithLocationData(player, context, x, y, z, yaw, pitch, worldName, locationName);
    }

    /**
     * Complete the task with location data.
     * The backend will process this and create/update the Location entity.
     */
    private void completeTaskWithLocationData(Player player, TaskContext context, double x, double y, double z,
                                               float yaw, float pitch, String worldName, String locationName) {
        // Build output JSON with location data
        // The backend workflow finalization process will handle creating the Location entity
        JsonObject output = new JsonObject();
        output.addProperty("fieldName", FIELD_NAME);
        output.addProperty("name", locationName);
        output.addProperty("x", x);
        output.addProperty("y", y);
        output.addProperty("z", z);
        output.addProperty("yaw", yaw);
        output.addProperty("pitch", pitch);
        output.addProperty("World", worldName);
        output.addProperty("capturedAt", System.currentTimeMillis());
        
        String outputJson = output.toString();

        // Validate again before completing (safety net)
        Location validationLocation = context.capturedLocation;
        if (validationLocation == null) {
            validationLocation = new Location(player.getWorld(), x, y, z, yaw, pitch);
        }

        ValidationResult validation = validateLocation(player, validationLocation, context);
        if (!validation.isValid() && validation.isBlocking()) {
            player.sendMessage("§c✗ Validation Failed:");
            player.sendMessage("§c" + validation.getMessage());
            player.sendMessage("§eTask not completed. Please move to a valid location and type 'save' again.");
            player.sendMessage("§eOr type 'cancel' to abort the task.");
            return;
        }

        // Complete the task via API
        // Backend will extract this data and create Location entity during finalization
        worldTasksApi.complete(context.taskId, outputJson)
            .thenAccept(completedTask -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    activeTasksByPlayer.remove(player);
                    player.sendMessage("§a[WorldTask] ✓ Task completed! Location captured.");
                    LOGGER.info("Completed Location task for player " + player.getName() 
                        + " (task " + context.taskId + ") named '" + locationName + "' at position: (" + x + ", " + y + ", " + z + ")");
                });
            })
            .exceptionally(ex -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§c[WorldTask] Failed to complete task: " + ex.getMessage());
                    LOGGER.warning("Failed to complete Location task " + context.taskId + ": " + ex.getMessage());
                });
                return null;
            });
    }

    /**
     * Resolve the location name from player input while preserving current default behavior.
     * If no name is provided, defaults to "Location".
     */
    private String resolveLocationName(String providedLocationName) {
        if (providedLocationName == null) {
            return FIELD_NAME;
        }

        String trimmed = providedLocationName.trim();
        return trimmed.isEmpty() ? FIELD_NAME : trimmed;
    }

    /**
     * Validate location against validation rules from InputJson.
     * Executes all configured validation rules and returns the first failure.
     * 
     * @param player The player
     * @param location The captured location
     * @param context Task context containing InputJson with validation rules
     * @return ValidationResult indicating success or failure
     */
    private ValidationResult validateLocation(Player player, Location location, TaskContext context) {
        try {
            // Parse validation context from InputJson
            LOGGER.info("validateLocation called for task " + context.taskId);
            LOGGER.info("InputJson: " + context.inputJson);
            
            if (context.inputJson == null || context.inputJson.isEmpty()) {
                LOGGER.info("InputJson is empty - skipping validation");
                return ValidationResult.success("No validation configured");
            }
            
            JsonObject input = JsonParser.parseString(context.inputJson).getAsJsonObject();
            if (!input.has("validationContext")) {
                LOGGER.info("No validationContext in InputJson - skipping validation");
                return ValidationResult.success("No validation configured");
            }
            
            JsonObject validationContext = input.getAsJsonObject("validationContext");
            if (!validationContext.has("validationRules")) {
                LOGGER.info("No validationRules in context - skipping validation");
                return ValidationResult.success("No validation rules");
            }
            
            JsonArray rules = validationContext.getAsJsonArray("validationRules");
            LOGGER.info("Found " + rules.size() + " validation rule(s)");
            
            // Execute each validation rule
            for (JsonElement ruleElement : rules) {
                JsonObject rule = ruleElement.getAsJsonObject();
                String validationType = rule.get("validationType").getAsString();
                LOGGER.info("Processing validation rule: " + validationType);
                
                if ("LocationInsideRegion".equals(validationType)) {
                    ValidationResult result = validateLocationInsideRegion(player, location, rule);
                    if (!result.isValid()) {
                        return result; // Return first failure
                    }
                }
                // Add more validation types here as needed
            }
            
            return ValidationResult.success("All validations passed");
            
        } catch (Exception e) {
            LOGGER.warning("Validation error for task " + context.taskId + ": " + e.getMessage());
            e.printStackTrace();
            // Fail-open: Allow task to proceed if validation parsing fails
            return ValidationResult.success("Validation skipped due to error");
        }
    }

    /**
     * Validate that a location is inside a WorldGuard region.
     * 
     * Supports multi-layer dependency path resolution:
     * - Direct property: "WgRegionId" (Layer 0)
     * - Single navigation: "Town.WgRegionId" (Layer 1)  
     * - Multi-layer: "District.Town.WgRegionId" (Layer 2+)
     * 
     * @param player The player
     * @param location The location to validate
     * @param rule The validation rule configuration
     * @return ValidationResult
     */
    private ValidationResult validateLocationInsideRegion(Player player, Location location, JsonObject rule) {
        try {
            // Parse config
            JsonObject config = JsonParser.parseString(rule.get("configJson").getAsString()).getAsJsonObject();

            //Log config for debugging
            LOGGER.info("Validating LocationInsideRegion with config: " + config.toString());
            
            // Get pre-resolved placeholders from InputJson (if provided by frontend)
            JsonObject preResolvedPlaceholders = new JsonObject();
            if (rule.has("preResolvedPlaceholders") && !rule.get("preResolvedPlaceholders").isJsonNull()) {
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
            
            // Extract parent region ID from dependency (e.g., Town entity)
            // The backend has already resolved the dependency path, so depValue contains
            // the final value from navigating the path (e.g., Town.WgRegionId → "town_1")
            String parentRegionId = extractParentRegionId(depValue, config);
            if (parentRegionId == null || parentRegionId.isEmpty()) {
                LOGGER.info("No parent region ID resolved - validation skipped");
                return ValidationResult.success("No parent region ID - validation skipped");
            }
            
            LOGGER.info("Resolved parent region ID: " + parentRegionId + " from dependency path: " + dependencyPath);
            
            // Get WorldGuard region manager
            RegionManager regionManager = WorldGuard.getInstance()
                .getPlatform()
                .getRegionContainer()
                .get(BukkitAdapter.adapt(location.getWorld()));
            
            if (regionManager == null) {
                return ValidationResult.blockingFailure("WorldGuard region manager not available for this world");
            }
            
            // Get parent region
            ProtectedRegion parentRegion = regionManager.getRegion(parentRegionId);
            if (parentRegion == null) {
                // Create computed placeholders
                JsonObject computedPlaceholders = new JsonObject();
                computedPlaceholders.addProperty("regionName", parentRegionId);
                
                // Merge with pre-resolved placeholders
                JsonObject allPlaceholders = PlaceholderInterpolationUtil.mergePlaceholders(
                    preResolvedPlaceholders, computedPlaceholders);
                
                // Interpolate error message
                String errorMsg = rule.get("errorMessage").getAsString();
                errorMsg = PlaceholderInterpolationUtil.interpolate(errorMsg, allPlaceholders);
                LOGGER.info("LocationInsideRegion validation failed: " + errorMsg);
                
                return ValidationResult.blockingFailure(errorMsg);
            }
            
            // Check if location is inside parent region
            BlockVector3 blockLoc = BlockVector3.at(location.getX(), location.getY(), location.getZ());
            boolean isInside = parentRegion.contains(blockLoc);
            
            if (!isInside) {
                // Create computed placeholders (plugin-only values)
                JsonObject computedPlaceholders = new JsonObject();
                computedPlaceholders.addProperty("regionName", parentRegion.getId());
                computedPlaceholders.addProperty("coordinates", 
                    String.format("(%.2f, %.2f, %.2f)", location.getX(), location.getY(), location.getZ()));
                
                // Merge pre-resolved placeholders from frontend with computed placeholders
                // Computed placeholders take precedence (override)
                JsonObject allPlaceholders = PlaceholderInterpolationUtil.mergePlaceholders(
                    preResolvedPlaceholders, computedPlaceholders);
                
                // Interpolate error message with all placeholders
                String errorMsg = rule.get("errorMessage").getAsString();
                errorMsg = PlaceholderInterpolationUtil.interpolate(errorMsg, allPlaceholders);
                
                boolean isBlocking = rule.has("isBlocking") && rule.get("isBlocking").getAsBoolean();
                return new ValidationResult(false, errorMsg, isBlocking);
            }
            
            // Success - interpolate success message if present
            String successMsg = "Location is inside region";
            if (rule.has("successMessage") && !rule.get("successMessage").isJsonNull()) {
                // Create computed placeholders for success message
                JsonObject computedPlaceholders = new JsonObject();
                computedPlaceholders.addProperty("regionName", parentRegion.getId());
                computedPlaceholders.addProperty("coordinates", 
                    String.format("(%.2f, %.2f, %.2f)", location.getX(), location.getY(), location.getZ()));
                
                JsonObject allPlaceholders = PlaceholderInterpolationUtil.mergePlaceholders(
                    preResolvedPlaceholders, computedPlaceholders);
                
                successMsg = rule.get("successMessage").getAsString();
                successMsg = PlaceholderInterpolationUtil.interpolate(successMsg, allPlaceholders);
            }
            
            player.sendMessage("§a✓ " + successMsg);
            return ValidationResult.success(successMsg);
            
        } catch (Exception e) {
            LOGGER.warning("LocationInsideRegion validation error: " + e.getMessage());
            e.printStackTrace();
            // Fail-open
            return ValidationResult.success("Validation skipped due to error");
        }
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
            String regionPath = config.has("regionPropertyPath") 
                ? config.get("regionPropertyPath").getAsString()
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

    /**
     * Check whether a world position (x,z) is inside the specified WorldGuard region.
     * Used by RegionHttpServer endpoint: /api/regions/{regionId}/contains
     */
    public static boolean checkLocationInsideRegion(String regionId, double x, double z, boolean allowBoundary) {
        return checkLocationInsideRegion(regionId, x, z, allowBoundary, null);
    }

    /**
     * KNG-112: as {@link #checkLocationInsideRegion(String, double, double, boolean)}, against the region in
     * {@code worldName} only (a region id is unique per world). Null checks the first loaded world that has it.
     */
    public static boolean checkLocationInsideRegion(String regionId, double x, double z, boolean allowBoundary,
                                                    String worldName) {
        if (regionId == null || regionId.trim().isEmpty()) {
            LOGGER.warning("Cannot check location containment: regionId is null/empty");
            return false;
        }

        try {
            List<World> worlds = Bukkit.getWorlds();
            if (worldName != null && !worldName.isBlank()) {
                World named = Bukkit.getWorld(worldName.trim());
                worlds = named == null ? List.of() : List.of(named);
            }
            for (World world : worlds) {
                RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                    .get(BukkitAdapter.adapt(world));

                if (regionManager == null) {
                    continue;
                }

                ProtectedRegion region = regionManager.getRegion(regionId);
                if (region == null) {
                    continue;
                }

                var minPoint = region.getMinimumPoint();
                var probe = BlockVector3.at(x, minPoint.getY(), z);

                boolean contains = region.contains(probe);
                if (!contains) {
                    LOGGER.info("Location containment check: region=" + regionId + ", x=" + x + ", z=" + z + " -> false");
                    return false;
                }

                if (!allowBoundary) {
                    var epsilon = 0.001d;
                    var probeLeft = BlockVector3.at(x - epsilon, minPoint.getY(), z);
                    var probeRight = BlockVector3.at(x + epsilon, minPoint.getY(), z);
                    var probeNorth = BlockVector3.at(x, minPoint.getY(), z - epsilon);
                    var probeSouth = BlockVector3.at(x, minPoint.getY(), z + epsilon);

                    boolean strictlyInside =
                        region.contains(probeLeft) &&
                        region.contains(probeRight) &&
                        region.contains(probeNorth) &&
                        region.contains(probeSouth);

                    LOGGER.info("Location containment check (strict): region=" + regionId + ", x=" + x + ", z=" + z + " -> " + strictlyInside);
                    return strictlyInside;
                }

                LOGGER.info("Location containment check (allowBoundary): region=" + regionId + ", x=" + x + ", z=" + z + " -> true");
                return true;
            }

            LOGGER.warning("Cannot check location containment: region not found in loaded worlds: " + regionId);
            return false;
        } catch (Exception e) {
            LOGGER.warning("Error checking location containment for region " + regionId + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
