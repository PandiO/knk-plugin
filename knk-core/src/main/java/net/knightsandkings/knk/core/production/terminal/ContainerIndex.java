package net.knightsandkings.knk.core.production.terminal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every bound container by its canonical block and, for displays, by chunk (PandiO/knk-workspace#74). Bindings are never
 * found by scanning (DESIGN § 6.2): the index is the API's list, re-read every few minutes and after a bind or unbind,
 * so opening a chest needs no API call. Replaced as a whole; reads are lock-free.
 */
public final class ContainerIndex {

    private record Snapshot(Map<BlockKey, StorageContainerInfo> byBlock,
                            Map<String, Map<Long, List<StorageContainerInfo>>> displaysByChunk) {
    }

    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());

    /** Replaces the index with {@code containers}. */
    public void replace(Collection<StorageContainerInfo> containers) {
        Map<BlockKey, StorageContainerInfo> byBlock = new HashMap<>();
        Map<String, Map<Long, List<StorageContainerInfo>>> chunks = new HashMap<>();
        for (StorageContainerInfo info : containers) {
            byBlock.put(info.block(), info);
            if (info.mode() == StorageContainerInfo.Mode.DISPLAY) {
                chunks.computeIfAbsent(info.worldName(), w -> new HashMap<>())
                        .computeIfAbsent(info.block().chunkKey(), c -> new ArrayList<>()).add(info);
            }
        }
        snapshot = new Snapshot(Map.copyOf(byBlock), chunks);
    }

    /** Adds or replaces one binding (after a bind). */
    public void put(StorageContainerInfo info) {
        List<StorageContainerInfo> all = new ArrayList<>(snapshot.byBlock().values());
        all.removeIf(c -> c.id() == info.id() || c.block().equals(info.block()));
        all.add(info);
        replace(all);
    }

    /** Drops one binding (after an unbind). */
    public void remove(int containerId) {
        List<StorageContainerInfo> all = new ArrayList<>(snapshot.byBlock().values());
        if (all.removeIf(c -> c.id() == containerId)) {
            replace(all);
        }
    }

    public Optional<StorageContainerInfo> at(BlockKey block) {
        return Optional.ofNullable(snapshot.byBlock().get(block));
    }

    public List<StorageContainerInfo> displaysInChunk(String world, int chunkX, int chunkZ) {
        Map<Long, List<StorageContainerInfo>> chunks = snapshot.displaysByChunk().get(world);
        if (chunks == null) {
            return List.of();
        }
        return chunks.getOrDefault(BlockKey.chunkKey(chunkX, chunkZ), List.of());
    }

    public List<StorageContainerInfo> displays() {
        return snapshot.byBlock().values().stream().filter(c -> c.mode() == StorageContainerInfo.Mode.DISPLAY).toList();
    }

    public List<StorageContainerInfo> displaysOf(int storageId) {
        return displays().stream().filter(c -> c.storageId() == storageId).toList();
    }

    public Optional<StorageContainerInfo> byId(int containerId) {
        return snapshot.byBlock().values().stream().filter(c -> c.id() == containerId).findFirst();
    }

    public int size() {
        return snapshot.byBlock().size();
    }
}
