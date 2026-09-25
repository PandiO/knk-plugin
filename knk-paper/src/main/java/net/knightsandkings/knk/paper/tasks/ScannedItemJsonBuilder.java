package net.knightsandkings.knk.paper.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.knightsandkings.knk.api.impl.enchantment.LocalEnchantmentRepositoryImpl;
import net.knightsandkings.knk.core.ports.enchantment.EnchantmentRepository;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Per-item scan JSON shared by {@link ItemScanTaskHandler} and {@link KitScanTaskHandler}
 * (docs/specs/kits/DESIGN.md §6.1: "reuse, don't reimplement"). Extracted verbatim from
 * ItemScanTaskHandler's original buildOutputJson, so ItemScan's own output is unchanged.
 *
 * {@link #build} emits, in this order: material, maxStackSize, displayName, lore,
 * vanillaEnchantments, customEnchantments, persistentDataContainer. Callers add their own
 * envelope (fieldName/status/warnings/capturedAt) and any extra per-item fields (KitScan adds
 * quantity and slot) - stack size is deliberately not part of this shared shape, since ItemScan
 * never emitted it.
 */
public class ScannedItemJsonBuilder {
    private static final Logger LOGGER = Logger.getLogger(ScannedItemJsonBuilder.class.getName());

    private final EnchantmentRepository customEnchantmentRepository;

    public ScannedItemJsonBuilder() {
        this(new LocalEnchantmentRepositoryImpl());
    }

    public ScannedItemJsonBuilder(EnchantmentRepository customEnchantmentRepository) {
        this.customEnchantmentRepository = customEnchantmentRepository;
    }

    /**
     * Builds the per-item JSON for {@code item}. An air/empty stack still produces an object
     * (material "minecraft:air", displayName null) - deciding whether an empty slot is reported
     * at all is the caller's call.
     */
    public JsonObject build(ItemStack item) {
        JsonObject result = new JsonObject();
        boolean isEmpty = item.getType().isAir();

        result.addProperty("material", item.getType().getKey().toString());

        // Per developer feedback (2026-09-23 live testing): MaxStackSize should come from the
        // scanned material itself (it genuinely varies per item - 64 for most, 16 for e.g. snowballs,
        // 1 for tools/weapons/armor) rather than a fixed form default, which only makes sense for
        // DefaultQuantity (handled by the live FormConfiguration's own defaultValue instead).
        result.addProperty("maxStackSize", item.getType().getMaxStackSize());

        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;

        // Per developer feedback (2026-09-23 live testing): most items are never renamed, so
        // ItemMeta.hasDisplayName() is false far more often than not - falling back to null left
        // DefaultDisplayName empty for the common case. Fall back to a humanized Material name
        // ("DIAMOND_SWORD" -> "Diamond Sword") instead, so vanilla item names are scanned too;
        // only truly empty scans leave displayName unset.
        String displayName = meta != null && meta.hasDisplayName()
            ? meta.getDisplayName()
            : (isEmpty ? null : humanizeMaterialName(item.getType()));

        if (displayName != null) {
            result.addProperty("displayName", displayName);
        } else {
            result.add("displayName", null);
        }

        List<String> lore = meta != null && meta.hasLore() ? meta.getLore() : List.of();
        JsonArray loreArray = new JsonArray();
        for (String line : lore) {
            loreArray.add(line);
        }
        result.add("lore", loreArray);

        JsonArray vanillaEnchantments = new JsonArray();
        if (meta != null) {
            for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                JsonObject enchantmentObj = new JsonObject();
                enchantmentObj.addProperty("key", entry.getKey().getKey().toString());
                enchantmentObj.addProperty("level", entry.getValue());
                vanillaEnchantments.add(enchantmentObj);
            }
        }
        result.add("vanillaEnchantments", vanillaEnchantments);

        // Reuse the existing lore-based custom-enchantment parser (already used by /ce info) -
        // per items IMPLEMENTATION_PLAN.md §5.2, this must not be reimplemented. Output the bare
        // id as-is (no "knk:" prefix): confirmed live against the real dev DB that
        // AbilityDefinition.SeedCanonicalAsync's EnchantmentDefinition rows for these same
        // canonical ids store Key as the bare id ("chaos", "armor_repair", ...), matching
        // EnchantmentRegistry's own ids exactly - not the "knk:lifesteal"-style prefix the
        // EnchantmentDefinition model's doc-comment suggests, which only reflects a convention
        // for admin-authored rows, not this scannable canonical set. A prefixed key here would
        // never match on the web-app's lookup.
        JsonArray customEnchantments = new JsonArray();
        Map<String, Integer> parsedCustomEnchantments = customEnchantmentRepository.getEnchantments(lore).join();
        for (Map.Entry<String, Integer> entry : parsedCustomEnchantments.entrySet()) {
            JsonObject enchantmentObj = new JsonObject();
            enchantmentObj.addProperty("key", entry.getKey());
            enchantmentObj.addProperty("level", entry.getValue());
            customEnchantments.add(enchantmentObj);
        }
        result.add("customEnchantments", customEnchantments);

        result.add("persistentDataContainer", readPersistentDataContainer(meta));
        return result;
    }

    /**
     * Reads every key in the item's PersistentDataContainer defensively, trying the common
     * primitive types in turn. Nothing in the plugin writes to item PDC yet (items plan §5.2 - no
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
    static String humanizeMaterialName(Material material) {
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
