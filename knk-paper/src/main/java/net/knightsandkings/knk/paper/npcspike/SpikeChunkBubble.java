package net.knightsandkings.knk.paper.npcspike;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * #243 spike (throwaway, never merged): the "travel bubble" (NAV-12). Plugin chunk tickets on the chunks a
 * travelling NPC needs next, counted per chunk because NPCs share chunks: the ticket is added when the first NPC
 * claims a chunk and removed when the last one lets go. Chunks are loaded asynchronously and never generated.
 * Main thread.
 */
final class SpikeChunkBubble {

    private final Plugin plugin;
    /** world name + chunk key → number of NPCs holding it. */
    private final Map<String, Map<Long, Integer>> refs = new HashMap<>();
    /** NPC id → the chunks it holds (world name + chunk key). */
    private final Map<Integer, Map<String, Set<Long>>> held = new HashMap<>();
    long loadsRequested;
    long notGenerated;

    SpikeChunkBubble(Plugin plugin) {
        this.plugin = plugin;
    }

    static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xffffffffL);
    }

    /** Chunks currently held by any NPC. */
    int chunksHeld() {
        int n = 0;
        for (Map<Long, Integer> world : refs.values()) {
            n += world.size();
        }
        return n;
    }

    /** NPCs holding at least one chunk. */
    int npcsWithBubble() {
        int n = 0;
        for (Map<String, Set<Long>> worlds : held.values()) {
            if (worlds.values().stream().anyMatch(set -> !set.isEmpty())) {
                n++;
            }
        }
        return n;
    }

    /**
     * Make NPC {@code npcId} hold exactly {@code chunks} (chunk x, z pairs) of {@code world}: claims the new ones,
     * lets go of the others. Completes on the main thread when every claimed chunk is loaded, with false when a chunk
     * is not generated (it is then not loaded, and not held).
     */
    CompletableFuture<Boolean> holdOnly(int npcId, World world, List<int[]> chunks) {
        Map<String, Set<Long>> worlds = held.computeIfAbsent(npcId, id -> new HashMap<>());
        Set<Long> mine = worlds.computeIfAbsent(world.getName(), w -> new HashSet<>());
        Set<Long> wanted = new HashSet<>();
        for (int[] c : chunks) {
            wanted.add(key(c[0], c[1]));
        }
        for (Long k : new ArrayList<>(mine)) {
            if (!wanted.contains(k)) {
                release(world, k);
                mine.remove(k);
            }
        }
        List<CompletableFuture<Boolean>> loads = new ArrayList<>();
        for (int[] c : chunks) {
            long k = key(c[0], c[1]);
            if (mine.add(k)) {
                loads.add(claim(world, c[0], c[1], k, npcId));
            } else if (!world.isChunkLoaded(c[0], c[1])) {
                loads.add(load(world, c[0], c[1]));
            }
        }
        return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new))
            .thenApply(v -> loads.stream().allMatch(CompletableFuture::join));
    }

    /** Let go of every chunk NPC {@code npcId} holds. */
    void releaseAll(int npcId, java.util.function.Function<String, World> worlds) {
        Map<String, Set<Long>> mine = held.remove(npcId);
        if (mine == null) {
            return;
        }
        mine.forEach((worldName, keys) -> {
            World world = worlds.apply(worldName);
            for (long k : keys) {
                release(world, worldName, k);
            }
        });
    }

    /** Remove every ticket (shutdown, clear). */
    void releaseEverything(java.util.function.Function<String, World> worlds) {
        for (Integer id : new ArrayList<>(held.keySet())) {
            releaseAll(id, worlds);
        }
        refs.clear();
    }

    private CompletableFuture<Boolean> claim(World world, int cx, int cz, long k, int npcId) {
        Map<Long, Integer> worldRefs = refs.computeIfAbsent(world.getName(), w -> new HashMap<>());
        int before = worldRefs.getOrDefault(k, 0);
        worldRefs.put(k, before + 1);
        if (before > 0 && world.isChunkLoaded(cx, cz)) {
            return CompletableFuture.completedFuture(true);
        }
        return load(world, cx, cz).thenApply(ok -> {
            if (!ok) {
                // not generated: give the claim back, nothing is held there
                Map<String, Set<Long>> worlds = held.get(npcId);
                if (worlds != null && worlds.get(world.getName()) != null) {
                    worlds.get(world.getName()).remove(k);
                }
                decrement(world.getName(), k);
            }
            return ok;
        });
    }

    private CompletableFuture<Boolean> load(World world, int cx, int cz) {
        CompletableFuture<Boolean> out = new CompletableFuture<>();
        loadsRequested++;
        // generate = false: an NPC never makes the server create new terrain
        world.getChunkAtAsync(cx, cz, false, false, (Chunk chunk) -> {
            if (chunk == null) {
                notGenerated++;
                out.complete(false);
                return;
            }
            Map<Long, Integer> worldRefs = refs.get(world.getName());
            if (worldRefs != null && worldRefs.getOrDefault(key(cx, cz), 0) > 0) {
                chunk.addPluginChunkTicket(plugin);
            }
            out.complete(true);
        });
        return out;
    }

    private void release(World world, long k) {
        release(world, world.getName(), k);
    }

    private void release(World world, String worldName, long k) {
        if (decrement(worldName, k) == 0 && world != null) {
            int cx = (int) (k >> 32);
            int cz = (int) k;
            world.removePluginChunkTicket(cx, cz, plugin);
        }
    }

    /** Lowers the count; returns what is left (0: nobody holds the chunk any more). */
    private int decrement(String worldName, long k) {
        Map<Long, Integer> worldRefs = refs.get(worldName);
        if (worldRefs == null) {
            return 0;
        }
        int left = worldRefs.getOrDefault(k, 0) - 1;
        if (left <= 0) {
            worldRefs.remove(k);
            return 0;
        }
        worldRefs.put(k, left);
        return left;
    }
}
