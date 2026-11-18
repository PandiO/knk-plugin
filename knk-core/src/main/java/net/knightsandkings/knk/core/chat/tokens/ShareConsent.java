package net.knightsandkings.knk.core.chat.tokens;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Never ask" answers of the share prompt (PandiO/knk-workspace#233): per player, the statistics settings
 * ({@code pvp_kills}, {@code deaths}, …) they keep private without being asked again when they type a token for them.
 * In memory and thread-safe; the plugin persists {@link #snapshot()} and loads it back with {@link #load}.
 */
public final class ShareConsent {

    private final Map<UUID, Set<String>> neverAsk = new ConcurrentHashMap<>();

    public boolean neverAsk(UUID player, String settingKey) {
        Set<String> keys = neverAsk.get(player);
        return keys != null && keys.contains(settingKey);
    }

    /** True when every key is "never ask" (and there is at least one). */
    public boolean neverAskAll(UUID player, List<String> settingKeys) {
        return !settingKeys.isEmpty() && settingKeys.stream().allMatch(key -> neverAsk(player, key));
    }

    public void addNeverAsk(UUID player, List<String> settingKeys) {
        neverAsk.computeIfAbsent(player, id -> ConcurrentHashMap.newKeySet()).addAll(settingKeys);
    }

    /** Made public after all: ask again if it is ever private again. */
    public void clear(UUID player, List<String> settingKeys) {
        Set<String> keys = neverAsk.get(player);
        if (keys != null) {
            keys.removeAll(settingKeys);
        }
    }

    public Map<UUID, Set<String>> snapshot() {
        Map<UUID, Set<String>> copy = new java.util.HashMap<>();
        neverAsk.forEach((player, keys) -> {
            if (!keys.isEmpty()) {
                copy.put(player, Set.copyOf(keys));
            }
        });
        return copy;
    }

    public void load(Map<UUID, ? extends java.util.Collection<String>> stored) {
        neverAsk.clear();
        stored.forEach((player, keys) -> addNeverAsk(player, List.copyOf(keys)));
    }
}
