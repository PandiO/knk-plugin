package net.knightsandkings.knk.paper.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.knightsandkings.knk.api.impl.enchantment.LocalEnchantmentRepositoryImpl;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
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
    private final EnchantmentRepository customEnchantmentRepository;

    public ItemScanTaskHandler(WorldTasksApi worldTasksApi, Plugin plugin) {
        this.worldTasksApi = worldTasksApi;
        this.plugin = plugin;
        this.customEnchantmentRepository = new LocalEnchantmentRepositoryImpl();
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
        root.addProperty("material", heldItem.getType().getKey().toString());

        boolean isEmptyHand = heldItem.getType().isAir();
        if (isEmptyHand) {
            warnings.add("Player was not holding an item in their main hand; scan captured an empty item.");
        }

        ItemMeta meta = heldItem.hasItemMeta() ? heldItem.getItemMeta() : null;

        // Per developer feedback (2026-09-23 live testing): most items are never renamed, so
        // ItemMeta.hasDisplayName() is false far more often than not - falling back to null left
        // DefaultDisplayName empty for the common case. Fall back to a humanized Material name
        // ("DIAMOND_SWORD" -> "Diamond Sword") instead, so vanilla item names are scanned too;
        // only truly empty-handed scans (isEmptyHand above) leave displayName unset.
        String displayName = meta != null && meta.hasDisplayName()
            ? meta.getDisplayName()
            : (isEmptyHand ? null : humanizeMaterialName(heldItem.getType()));

        if (displayName != null) {
            root.addProperty("displayName", displayName);
        } else {
            root.add("displayName", null);
        }

        List<String> lore = meta != null && meta.hasLore() ? meta.getLore() : List.of();
        JsonArray loreArray = new JsonArray();
        for (String line : lore) {
            loreArray.add(line);
        }
        root.add("lore", loreArray);

        JsonArray vanillaEnchantments = new JsonArray();
        if (meta != null) {
            for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                JsonObject enchantmentObj = new JsonObject();
                enchantmentObj.addProperty("key", entry.getKey().getKey().toString());
                enchantmentObj.addProperty("level", entry.getValue());
                vanillaEnchantments.add(enchantmentObj);
            }
        }
        root.add("vanillaEnchantments", vanillaEnchantments);

        // Reuse the existing lore-based custom-enchantment parser (already used by /ce info) -
        // per §5.2, this must not be reimplemented. Output the bare id as-is (no "knk:" prefix):
        // confirmed live against the real dev DB that AbilityDefinition.SeedCanonicalAsync's
        // EnchantmentDefinition rows for these same canonical ids store Key as the bare id
        // ("chaos", "armor_repair", ...), matching EnchantmentRegistry's own ids exactly - not
        // the "knk:lifesteal"-style prefix the EnchantmentDefinition model's doc-comment
        // suggests, which only reflects a convention for admin-authored rows, not this scannable
        // canonical set. A prefixed key here would never match on the web-app's lookup.
        JsonArray customEnchantments = new JsonArray();
        Map<String, Integer> parsedCustomEnchantments = customEnchantmentRepository.getEnchantments(lore).join();
        for (Map.Entry<String, Integer> entry : parsedCustomEnchantments.entrySet()) {
            JsonObject enchantmentObj = new JsonObject();
            enchantmentObj.addProperty("key", entry.getKey());
            enchantmentObj.addProperty("level", entry.getValue());
            customEnchantments.add(enchantmentObj);
        }
        root.add("customEnchantments", customEnchantments);

        root.add("persistentDataContainer", readPersistentDataContainer(meta));
        root.addProperty("capturedAt", System.currentTimeMillis());

        String status = warnings.isEmpty() ? "Success" : "Warning";
        root.addProperty("status", status);
        root.add("warnings", warnings);

        return root.toString();
    }

    /**
     * Reads every key in the item's PersistentDataContainer defensively, trying the common
     * primitive types in turn. Nothing in the plugin writes to item PDC yet (§5.2 - no
     * ItemInstance exists), so this reads empty for every item today; that's expected, not a
     * bug to chase. Kept forward-compatible for whenever something does start writing to it.
     */
    private JsonObject readPersistentDataContainer(ItemMeta meta) {
        JsonObject result = new JsonObject();
        if (meta == null) {
            return result;
        }

        try {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            for (NamespacedKey key : pdc.getKeys()) {
                String value = readPdcValueAsString(pdc, key);
                if (value != null) {
                    result.addProperty(key.toString(), value);
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to read PersistentDataContainer during item scan: " + e.getMessage());
        }

        return result;
    }

    private String readPdcValueAsString(PersistentDataContainer pdc, NamespacedKey key) {
        try {
            if (pdc.has(key, PersistentDataType.STRING)) {
                return pdc.get(key, PersistentDataType.STRING);
            }
            if (pdc.has(key, PersistentDataType.INTEGER)) {
                return String.valueOf(pdc.get(key, PersistentDataType.INTEGER));
            }
            if (pdc.has(key, PersistentDataType.LONG)) {
                return String.valueOf(pdc.get(key, PersistentDataType.LONG));
            }
            if (pdc.has(key, PersistentDataType.DOUBLE)) {
                return String.valueOf(pdc.get(key, PersistentDataType.DOUBLE));
            }
            if (pdc.has(key, PersistentDataType.FLOAT)) {
                return String.valueOf(pdc.get(key, PersistentDataType.FLOAT));
            }
            if (pdc.has(key, PersistentDataType.BYTE)) {
                return String.valueOf(pdc.get(key, PersistentDataType.BYTE));
            }
        } catch (Exception e) {
            LOGGER.fine("Could not read PDC key " + key + " with a known primitive type: " + e.getMessage());
        }
        return "<unreadable>";
    }

    /**
     * "DIAMOND_SWORD" -> "Diamond Sword". Bukkit has no client-authoritative "translated name"
     * API without NMS (real translation happens client-side from the resource pack's lang file),
     * so this is a best-effort fallback for un-renamed items, matching the same word-splitting
     * convention {@code EnchantmentDefinitionsDebugCommand.toDisplayName} already uses for
     * vanilla enchantment keys.
     */
    private String humanizeMaterialName(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                builder.append(word.substring(1));
            }
        }
        return builder.toString();
    }
}
