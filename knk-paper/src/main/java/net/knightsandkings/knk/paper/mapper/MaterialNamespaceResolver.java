package net.knightsandkings.knk.paper.mapper;

import org.bukkit.Material;

import java.util.Locale;

/**
 * Resolves a persisted {@code KnkMinecraftMaterialRef.namespaceKey} (e.g.
 * "minecraft:diamond_sword") to a Bukkit {@link Material}, trying a direct
 * match, then the bare key part, then an upper-snake-case enum token.
 * <p>
 * Extracted from {@code ItemBlueprintBukkitMapper} (its original, single
 * caller) so the same resolution logic can also back menu item/background
 * material rendering without duplicating it.
 */
public final class MaterialNamespaceResolver {

    private MaterialNamespaceResolver() {
    }

    public static Material resolve(String namespaceKey) {
        if (namespaceKey == null || namespaceKey.isBlank()) {
            return null;
        }

        Material direct = Material.matchMaterial(namespaceKey);
        if (direct != null) {
            return direct;
        }

        String keyPart = namespaceKey.contains(":")
                ? namespaceKey.substring(namespaceKey.indexOf(':') + 1)
                : namespaceKey;

        Material byKeyPart = Material.matchMaterial(keyPart);
        if (byKeyPart != null) {
            return byKeyPart;
        }

        String enumToken = keyPart.toUpperCase(Locale.ROOT).replace('-', '_');
        return Material.matchMaterial(enumToken);
    }
}
