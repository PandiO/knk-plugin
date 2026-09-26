package net.knightsandkings.knk.paper.mapper;

import net.knightsandkings.knk.core.domain.enchantment.EnchantmentRegistry;
import net.knightsandkings.knk.core.domain.enchantments.KnkEnchantmentDefinition;
import net.knightsandkings.knk.core.domain.item.KnkItemBlueprintDefaultEnchantment;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;

import java.util.Locale;

/**
 * Converts persisted EnchantmentDefinition data into Bukkit enchantment resolution data.
 */
public final class EnchantmentDefinitionBukkitMapper {

    private EnchantmentDefinitionBukkitMapper() {
    }

    /**
     * A definition built from an ItemBlueprint default enchantment's denormalized fields (key, display
     * name, isCustom, maxLevel) - enough for {@link #toBukkit}/{@link #toCustom} without fetching the
     * full definition. Used by {@code /knk itemblueprints give} as its fallback and by permanent
     * enchantment books (KNG-5), which are built synchronously from the blueprint.
     */
    public static KnkEnchantmentDefinition fromDefaultEnchantment(KnkItemBlueprintDefaultEnchantment relation) {
        if (relation == null) {
            return null;
        }

        String key = relation.enchantmentKey();
        String baseNamespace = key != null && key.startsWith("minecraft:") ? key : null;

        return new KnkEnchantmentDefinition(
                relation.enchantmentDefinitionId(),
                key,
                relation.enchantmentDisplayName(),
                null,
                relation.enchantmentIsCustom(),
                relation.enchantmentMaxLevel(),
                null,
                baseNamespace
        );
    }

    public static BukkitEnchantmentResolution toBukkit(KnkEnchantmentDefinition definition) {
        if (definition == null) {
            return BukkitEnchantmentResolution.invalid("Enchantment definition is null");
        }

        String namespaceKey = resolveNamespaceKey(definition);
        if (namespaceKey == null || namespaceKey.isBlank()) {
            return BukkitEnchantmentResolution.invalid(
                    "No valid Minecraft enchantment namespace key found (expected baseEnchantmentNamespaceKey or minecraft:* key)"
            );
        }

        NamespacedKey namespacedKey = NamespacedKey.fromString(namespaceKey);
        if (namespacedKey == null) {
            return BukkitEnchantmentResolution.invalid("Invalid namespace key format: " + namespaceKey);
        }

        Enchantment enchantment = Registry.ENCHANTMENT.get(namespacedKey);
        if (enchantment == null) {
            enchantment = Enchantment.getByKey(namespacedKey);
        }

        if (enchantment == null) {
            return BukkitEnchantmentResolution.invalid(
                    "Enchantment is not registered on this server: " + namespaceKey
            );
        }

        int configuredMaxLevel = definition.maxLevel() != null ? definition.maxLevel() : 1;
        int defaultLevel = Math.max(1, Math.min(configuredMaxLevel, enchantment.getMaxLevel()));

        return new BukkitEnchantmentResolution(enchantment, namespaceKey, defaultLevel, null);
    }

    private static String resolveNamespaceKey(KnkEnchantmentDefinition definition) {
        if (definition.baseEnchantmentNamespaceKey() != null && !definition.baseEnchantmentNamespaceKey().isBlank()) {
            return definition.baseEnchantmentNamespaceKey();
        }

        if (definition.key() != null && definition.key().startsWith("minecraft:")) {
            return definition.key();
        }

        return null;
    }

    public static CustomEnchantmentResolution toCustom(KnkEnchantmentDefinition definition) {
        if (definition == null) {
            return CustomEnchantmentResolution.invalid("Enchantment definition is null");
        }

        if (!Boolean.TRUE.equals(definition.isCustom())) {
            return CustomEnchantmentResolution.invalid("Enchantment definition is not marked as custom");
        }

        String resolvedEnchantmentId = resolveCustomEnchantmentId(definition);
        if (resolvedEnchantmentId == null || resolvedEnchantmentId.isBlank()) {
            return CustomEnchantmentResolution.invalid(
                    "Unable to resolve custom enchantment id from key/displayName"
            );
        }

        int configuredMaxLevel = definition.maxLevel() != null ? definition.maxLevel() : 1;
        int defaultLevel = Math.max(1, Math.min(configuredMaxLevel, 3));

        int effectiveMaxLevel = EnchantmentRegistry.getInstance()
                .getById(resolvedEnchantmentId)
                .map(enchantment -> Math.max(1, Math.min(configuredMaxLevel, enchantment.maxLevel())))
                .orElse(defaultLevel);

        return new CustomEnchantmentResolution(resolvedEnchantmentId, defaultLevel, effectiveMaxLevel, null);
    }

    private static String resolveCustomEnchantmentId(KnkEnchantmentDefinition definition) {
        EnchantmentRegistry registry = EnchantmentRegistry.getInstance();

        String[] candidates = new String[] {
                normalizeCustomCandidate(definition.key()),
                normalizeCustomCandidate(extractKeyPart(definition.key())),
                normalizeCustomCandidate(definition.displayName())
        };

        for (String candidate : candidates) {
            if (candidate != null && registry.getById(candidate).isPresent()) {
                return candidate;
            }
        }

        return null;
    }

    private static String extractKeyPart(String key) {
        if (key == null) {
            return null;
        }

        int separator = key.indexOf(':');
        if (separator < 0 || separator >= key.length() - 1) {
            return key;
        }

        return key.substring(separator + 1);
    }

    private static String normalizeCustomCandidate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }

    public record BukkitEnchantmentResolution(
            Enchantment enchantment,
            String namespaceKey,
            int defaultLevel,
            String error
    ) {
        public boolean isValid() {
            return enchantment != null && error == null;
        }

        public static BukkitEnchantmentResolution invalid(String error) {
            return new BukkitEnchantmentResolution(null, null, 0, error);
        }
    }

    public record CustomEnchantmentResolution(
            String enchantmentId,
            int defaultLevel,
            int maxLevel,
            String error
    ) {
        public boolean isValid() {
            return enchantmentId != null && !enchantmentId.isBlank() && error == null;
        }

        public static CustomEnchantmentResolution invalid(String error) {
            return new CustomEnchantmentResolution(null, 0, 0, error);
        }
    }
}
