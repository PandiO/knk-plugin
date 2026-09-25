package net.knightsandkings.knk.paper.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Handler for ItemScan field tasks (docs/specs/items/IMPLEMENTATION_PLAN.md §5).
 *
 * Unlike Location/WgRegionId, this is a single-shot capture: an item scan fundamentally
 * requires a specific player holding a specific physical item at the moment of the scan, so
 * there is nothing to "pause"/"resume"/wait for chat input on - {@link #startTask} reads the
 * player's held item and completes the task synchronously, right at claim/invocation time
 * (§5.1). This also matches {@link GateBlockScanTaskHandler}'s headless sibling only in output
 * shape, not in execution model - this handler is player-driven ({@link IWorldTaskHandler}),
 * not headless, since there is no world location to revisit later without a player present.
 */
public class ItemScanTaskHandler implements IWorldTaskHandler {
    private static final Logger LOGGER = Logger.getLogger(ItemScanTaskHandler.class.getName());
    private static final String FIELD_NAME = "ItemScan";

    private final WorldTasksApi worldTasksApi;
    private final Plugin plugin;
    private final ScannedItemJsonBuilder itemJsonBuilder;

    public ItemScanTaskHandler(WorldTasksApi worldTasksApi, Plugin plugin) {
        this(worldTasksApi, plugin, new ScannedItemJsonBuilder());
    }

    public ItemScanTaskHandler(WorldTasksApi worldTasksApi, Plugin plugin, ScannedItemJsonBuilder itemJsonBuilder) {
        this.worldTasksApi = worldTasksApi;
        this.plugin = plugin;
        this.itemJsonBuilder = itemJsonBuilder;
    }

    @Override
    public String getFieldName() {
        return FIELD_NAME;
    }

    @Override
    public void startTask(Player player, int taskId, String inputJson) {
        // Called on the main thread by both entry points (KnkTaskClaimCommand's claim flow and
        // the dedicated /knk itemscan claim command both dispatch via Bukkit.getScheduler().
        // runTask before reaching WorldTaskHandlerRegistry.startTask) - safe to touch the
        // player's inventory/ItemMeta directly here, no scheduling needed.
        player.sendMessage("§6[WorldTask] Scanning your held item...");

        try {
            String outputJson = buildOutputJson(player);
            worldTasksApi.complete(taskId, outputJson).thenAccept(completedTask -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§a[WorldTask] ✓ Item scanned!");
                    LOGGER.info("Completed ItemScan task for player " + player.getName() + " (task " + taskId + ")");
                });
            }).exceptionally(ex -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§c[WorldTask] Failed to complete task: " + ex.getMessage());
                    LOGGER.warning("Failed to complete ItemScan task " + taskId + ": " + ex.getMessage());
                });
                return null;
            });
        } catch (Exception e) {
            player.sendMessage("§c[WorldTask] Error scanning item: " + e.getMessage());
            LOGGER.warning("Error scanning item for ItemScan task " + taskId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public boolean isHandling(Player player) {
        // No multi-step session exists for this handler - the scan completes synchronously
        // inside startTask, so there is never a "player is mid-task" state to report.
        return false;
    }

    @Override
    public void cancel(Player player) {
        // No-op: nothing to cancel, see isHandling.
    }

    @Override
    public Integer getTaskId(Player player) {
        return null;
    }

    private String buildOutputJson(Player player) {
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        JsonObject root = new JsonObject();
        JsonArray warnings = new JsonArray();

        root.addProperty("fieldName", FIELD_NAME);

        boolean isEmptyHand = heldItem.getType().isAir();
        if (isEmptyHand) {
            warnings.add("Player was not holding an item in their main hand; scan captured an empty item.");
        }

        // Per-item fields (material/maxStackSize/displayName/lore/enchantments/PDC) come from the
        // shared builder KitScanTaskHandler also uses (docs/specs/kits/DESIGN.md §6.1), copied
        // onto the root in the builder's order so this output keeps its original flat shape.
        for (Map.Entry<String, JsonElement> entry : itemJsonBuilder.build(heldItem).entrySet()) {
            root.add(entry.getKey(), entry.getValue());
        }

        root.addProperty("capturedAt", System.currentTimeMillis());

        String status = warnings.isEmpty() ? "Success" : "Warning";
        root.addProperty("status", status);
        root.add("warnings", warnings);

        return root.toString();
    }
}
