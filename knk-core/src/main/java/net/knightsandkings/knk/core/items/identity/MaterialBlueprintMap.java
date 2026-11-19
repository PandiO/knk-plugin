package net.knightsandkings.knk.core.items.identity;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The material → blueprint map knk-web-api keeps ({@code GET api/ItemIdentity/material-map}): each obtainable vanilla
 * material has exactly one blueprint, curated or a generated "vanilla shadow". Immutable; the plugin swaps the whole
 * map on a refresh, so the resolver never calls the API.
 */
public final class MaterialBlueprintMap {

    /** One material's blueprint; {@code shadow} for a generated, not yet curated one. */
    public record Entry(String materialKey, int blueprintId, boolean shadow) {
    }

    public static final MaterialBlueprintMap EMPTY = new MaterialBlueprintMap(Map.of());

    private final Map<String, Entry> byMaterial;

    private MaterialBlueprintMap(Map<String, Entry> byMaterial) {
        this.byMaterial = byMaterial;
    }

    /** Builds the map; keys are normalised ({@link #normalize}); a later duplicate key wins. Null entries are skipped. */
    public static MaterialBlueprintMap of(Collection<Entry> entries) {
        Map<String, Entry> map = new HashMap<>();
        if (entries != null) {
            for (Entry entry : entries) {
                if (entry == null || entry.blueprintId() <= 0) {
                    continue;
                }
                String key = normalize(entry.materialKey());
                if (key != null) {
                    map.put(key, new Entry(key, entry.blueprintId(), entry.shadow()));
                }
            }
        }
        return new MaterialBlueprintMap(Map.copyOf(map));
    }

    /** {@code OAK_LOG}, {@code oak_log} and {@code minecraft:oak_log} → {@code minecraft:oak_log}; null for blank. */
    public static String normalize(String materialKey) {
        if (materialKey == null || materialKey.isBlank()) {
            return null;
        }
        String key = materialKey.trim().toLowerCase(Locale.ROOT);
        return key.indexOf(':') >= 0 ? key : "minecraft:" + key;
    }

    public Optional<Entry> find(String materialKey) {
        String key = normalize(materialKey);
        return key == null ? Optional.empty() : Optional.ofNullable(byMaterial.get(key));
    }

    /** The material's blueprint id, or null. */
    public Integer blueprintIdOf(String materialKey) {
        return find(materialKey).map(Entry::blueprintId).orElse(null);
    }

    public int size() {
        return byMaterial.size();
    }

    public boolean isEmpty() {
        return byMaterial.isEmpty();
    }
}
