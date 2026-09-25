package net.knightsandkings.knk.paper.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Handler for KitScan field tasks (docs/specs/kits/DESIGN.md §6): captures the triggering
 * player's whole inventory so it can be relayed into the open Kit form.
 *
 * Same execution model as {@link ItemScanTaskHandler} (§6.2): single-shot and synchronous -
 * {@link #startTask} reads the inventory and completes the task in one call, there is no
 * multi-step session, and it is player-driven ({@link IWorldTaskHandler}), not headless, since
 * the scan needs a specific player's live inventory at the moment of the scan.
 *
 * Output shape (§6.3): helmet/chestplate/leggings/boots/shield/hand are each a scanned item or
 * null when that slot was empty; contents lists every non-empty storage slot 0-35 except the
 * held hotbar slot (already captured as hand), each tagged with its slot index. Every scanned
 * item is {@link ScannedItemJsonBuilder}'s per-item JSON plus its stack quantity.
 */
public class KitScanTaskHandler implements IWorldTaskHandler {
    private static final Logger LOGGER = Logger.getLogger(KitScanTaskHandler.class.getName());
    private static final String FIELD_NAME = "KitScan";
    private static final int STORAGE_SLOT_COUNT = 36;

    private final WorldTasksApi worldTasksApi;
    private final Plugin plugin;
    private final ScannedItemJsonBuilder itemJsonBuilder;

    public KitScanTaskHandler(WorldTasksApi worldTasksApi, Plugin plugin) {
        this(worldTasksApi, plugin, new ScannedItemJsonBuilder());
    }

    public KitScanTaskHandler(WorldTasksApi worldTasksApi, Plugin plugin, ScannedItemJsonBuilder itemJsonBuilder) {
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
        // Called on the main thread by both entry points (/knk task-claim and /knk kitscan claim
        // both go through KnkTaskClaimCommand, which dispatches via Bukkit.getScheduler().runTask
        // before reaching WorldTaskHandlerRegistry.startTask) - safe to read the inventory here.
        player.sendMessage("§6[WorldTask] Scanning your inventory...");

        try {
            String outputJson = buildOutputJson(player.getInventory());
            worldTasksApi.complete(taskId, outputJson).thenAccept(completedTask -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§a[WorldTask] ✓ Kit scanned!");
                    LOGGER.info("Completed KitScan task for player " + player.getName() + " (task " + taskId + ")");
                });
            }).exceptionally(ex -> {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage("§c[WorldTask] Failed to complete task: " + ex.getMessage());
                    LOGGER.warning("Failed to complete KitScan task " + taskId + ": " + ex.getMessage());
                });
                return null;
            });
        } catch (Exception e) {
            player.sendMessage("§c[WorldTask] Error scanning inventory: " + e.getMessage());
            LOGGER.warning("Error scanning inventory for KitScan task " + taskId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public boolean isHandling(Player player) {
        // No multi-step session - the scan completes synchronously inside startTask.
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

    String buildOutputJson(PlayerInventory inventory) {
        JsonObject root = new JsonObject();
        JsonArray warnings = new JsonArray();

        root.addProperty("fieldName", FIELD_NAME);

        root.add("helmet", scanSlot(inventory.getHelmet()));
        root.add("chestplate", scanSlot(inventory.getChestplate()));
        root.add("leggings", scanSlot(inventory.getLeggings()));
        root.add("boots", scanSlot(inventory.getBoots()));
        // Off-hand is captured as "shield" regardless of what it actually holds (§6.1).
        root.add("shield", scanSlot(inventory.getItemInOffHand()));
        // Main hand: no slot index recorded - at grant time "hand" targets whichever hotbar slot
        // is active then, not the index it was scanned from (§4.2/§6.1).
        root.add("hand", scanSlot(inventory.getItemInMainHand()));

        // getStorageContents() is slots 0-35 (hotbar 0-8, main inventory 9-35). The held slot is
        // skipped since that item was already captured as "hand" above.
        int heldSlot = inventory.getHeldItemSlot();
        ItemStack[] storage = inventory.getStorageContents();
        JsonArray contents = new JsonArray();
        for (int slot = 0; slot < Math.min(storage.length, STORAGE_SLOT_COUNT); slot++) {
            if (slot == heldSlot || isEmpty(storage[slot])) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("slot", slot);
            for (Map.Entry<String, JsonElement> field : scanItem(storage[slot]).entrySet()) {
                entry.add(field.getKey(), field.getValue());
            }
            contents.add(entry);
        }
        root.add("contents", contents);

        boolean anyEquipment = !root.get("helmet").isJsonNull() || !root.get("chestplate").isJsonNull()
            || !root.get("leggings").isJsonNull() || !root.get("boots").isJsonNull()
            || !root.get("shield").isJsonNull() || !root.get("hand").isJsonNull();
        if (!anyEquipment && contents.isEmpty()) {
            warnings.add("Player's inventory was empty; scan captured no items.");
        }

        root.addProperty("capturedAt", System.currentTimeMillis());
        root.addProperty("status", warnings.isEmpty() ? "Success" : "Warning");
        root.add("warnings", warnings);

        return root.toString();
    }

    private JsonElement scanSlot(ItemStack item) {
        return isEmpty(item) ? JsonNull.INSTANCE : scanItem(item);
    }

    private JsonObject scanItem(ItemStack item) {
        JsonObject result = itemJsonBuilder.build(item);
        result.addProperty("quantity", item.getAmount());
        return result;
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }
}
