package net.knightsandkings.knk.core.lootbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The plugin's view of the active boxes (docs/specs/lootboxes/DESIGN.md §3.4), keyed by id, token and chunk so the
 * chunk listener renders a loaded chunk's boxes without scanning all of them. Refreshed from
 * {@code GET api/LootboxSpawns/active} and kept current by local spawns, claims and despawns.
 * <p>
 * Only a cache: it never decides whether a box may be claimed (the API does). Thread-safe (every method is
 * synchronized), though the plugin only touches it from the main thread.
 */
public final class ActiveLootboxCache {

    /** A chunk of a world. */
    public record ChunkKey(String world, int chunkX, int chunkZ) {
        public static ChunkKey of(KnkLootboxSpawn spawn) {
            return new ChunkKey(spawn.world(), spawn.chunkX(), spawn.chunkZ());
        }
    }

    private final Map<Integer, KnkLootboxSpawn> byId = new HashMap<>();
    private final Map<UUID, Integer> idByToken = new HashMap<>();
    private final Map<ChunkKey, Set<Integer>> idsByChunk = new HashMap<>();

    /**
     * Replaces everything with {@code spawns} (a fresh {@code active} read) and returns the boxes that were cached
     * but are no longer active, so their entities can be removed.
     */
    public synchronized List<KnkLootboxSpawn> replaceAll(Collection<KnkLootboxSpawn> spawns) {
        Map<Integer, KnkLootboxSpawn> previous = new HashMap<>(byId);
        byId.clear();
        idByToken.clear();
        idsByChunk.clear();
        if (spawns != null) {
            for (KnkLootboxSpawn spawn : spawns) {
                if (spawn != null) {
                    add(spawn);
                    previous.remove(spawn.id());
                }
            }
        }
        return new ArrayList<>(previous.values());
    }

    /** Adds or replaces one box (a spawn this server just made). */
    public synchronized void put(KnkLootboxSpawn spawn) {
        if (spawn == null) {
            return;
        }
        remove(spawn.id());
        add(spawn);
    }

    /** Removes a box (claimed, despawned, expired); empty when it wasn't cached. */
    public synchronized Optional<KnkLootboxSpawn> remove(int spawnId) {
        KnkLootboxSpawn removed = byId.remove(spawnId);
        if (removed == null) {
            return Optional.empty();
        }
        idByToken.remove(removed.token());
        ChunkKey key = ChunkKey.of(removed);
        Set<Integer> ids = idsByChunk.get(key);
        if (ids != null) {
            ids.remove(spawnId);
            if (ids.isEmpty()) {
                idsByChunk.remove(key);
            }
        }
        return Optional.of(removed);
    }

    public synchronized Optional<KnkLootboxSpawn> byId(int spawnId) {
        return Optional.ofNullable(byId.get(spawnId));
    }

    public synchronized Optional<KnkLootboxSpawn> byToken(UUID token) {
        Integer id = token == null ? null : idByToken.get(token);
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    public synchronized boolean isActiveToken(UUID token) {
        return token != null && idByToken.containsKey(token);
    }

    public synchronized List<KnkLootboxSpawn> inChunk(String world, int chunkX, int chunkZ) {
        Set<Integer> ids = idsByChunk.get(new ChunkKey(world, chunkX, chunkZ));
        if (ids == null) {
            return List.of();
        }
        List<KnkLootboxSpawn> result = new ArrayList<>(ids.size());
        for (Integer id : ids) {
            KnkLootboxSpawn spawn = byId.get(id);
            if (spawn != null) {
                result.add(spawn);
            }
        }
        return result;
    }

    public synchronized List<KnkLootboxSpawn> all() {
        return new ArrayList<>(byId.values());
    }

    public synchronized int size() {
        return byId.size();
    }

    public synchronized int countInArea(int areaId) {
        int count = 0;
        for (KnkLootboxSpawn spawn : byId.values()) {
            if (spawn.spawnAreaId() != null && spawn.spawnAreaId() == areaId) {
                count++;
            }
        }
        return count;
    }

    /** Removes and returns every box whose {@code expiresAt} has passed. */
    public synchronized List<KnkLootboxSpawn> removeExpired(Instant now) {
        List<KnkLootboxSpawn> expired = new ArrayList<>();
        for (KnkLootboxSpawn spawn : new HashSet<>(byId.values())) {
            if (spawn.isExpired(now)) {
                expired.add(spawn);
            }
        }
        expired.forEach(spawn -> remove(spawn.id()));
        return expired;
    }

    private void add(KnkLootboxSpawn spawn) {
        byId.put(spawn.id(), spawn);
        if (spawn.token() != null) {
            idByToken.put(spawn.token(), spawn.id());
        }
        idsByChunk.computeIfAbsent(ChunkKey.of(spawn), k -> new HashSet<>()).add(spawn.id());
    }
}
