package net.knightsandkings.knk.core.territory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The game server's territory, all worlds (territory DESIGN § 1, PandiO/knk-workspace#689): built from one
 * {@link TerritoryInputs} and the region shapes of that moment, swapped in whole when rebuilt. Immutable.
 *
 * @param inputs              what it was built from (names, version, the API's flag)
 * @param geometryFingerprint a hash of the region shapes used: equal inputs and fingerprint mean nothing to rebuild
 * @param warnings            sources left out (region not found, chunk budget), for {@code /knk territory status}
 */
public record TerritoryCache(TerritoryInputs inputs, long geometryFingerprint, Map<String, TerritoryMap> worlds,
                             Instant builtAt, long buildMillis, List<String> warnings) {

    public static final TerritoryCache EMPTY = new TerritoryCache(TerritoryInputs.EMPTY, 0L, Map.of(), Instant.EPOCH, 0L,
            List.of());

    public TerritoryCache {
        worlds = Map.copyOf(worlds);
        warnings = List.copyOf(warnings);
    }

    /** The verdict at block column ({@code x}, {@code z}) of {@code world}; wilderness for a world without territory. */
    public TerritoryVerdict lookup(String world, int x, int z) {
        TerritoryMap map = world == null ? null : worlds.get(world);
        return map == null ? TerritoryVerdict.WILDERNESS : map.verdictAt(x, z);
    }

    /** Whether a lookup inside this chunk must be made per block column. */
    public boolean isSplit(String world, int cx, int cz) {
        TerritoryMap map = world == null ? null : worlds.get(world);
        return map != null && map.isSplit(cx, cz);
    }

    public String version() {
        return inputs.version();
    }

    public Set<String> worldNames() {
        return worlds.keySet();
    }

    public int chunkCount() {
        return worlds.values().stream().mapToInt(TerritoryMap::chunkCount).sum();
    }

    public int splitCount() {
        return worlds.values().stream().mapToInt(TerritoryMap::splitCount).sum();
    }

    /** A short human-readable description of a verdict ("Phoenix", "contested: Phoenix / Rivertown", "wilderness"). */
    public String describe(TerritoryVerdict verdict) {
        if (verdict == null || verdict.isWilderness()) {
            return "wilderness";
        }
        if (verdict.owner() != null) {
            return describe(verdict.owner());
        }
        StringBuilder text = new StringBuilder("contested: ");
        for (int i = 0; i < verdict.contesting().size(); i++) {
            if (i > 0) {
                text.append(" / ");
            }
            text.append(describe(verdict.contesting().get(i)));
        }
        return text.toString();
    }

    /** A holder's name; crown land is named after its kingdom ("crown land of Aurelia"). */
    public String describe(TerritoryHolder holder) {
        String name = inputs.nameOf(holder);
        return holder.kind() == TerritoryHolder.Kind.KINGDOM ? "crown land of " + name : name;
    }
}
