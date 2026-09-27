package net.knightsandkings.knk.paper.listeners;

import net.knightsandkings.knk.paper.lootbox.LootboxRuntime;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Keeps boxes visible across chunk loads (docs/specs/lootboxes/DESIGN.md §3.4, §3.7): a loaded chunk only
 * <b>re-renders</b> the boxes the cache already has there (no roll, no spawn), and any entity that comes back with a
 * box token that isn't active is removed (belt and braces: the entities are non-persistent).
 */
public final class LootboxChunkListener implements Listener {

    private final LootboxRuntime runtime;

    public LootboxChunkListener(LootboxRuntime runtime) {
        this.runtime = runtime;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        runtime.renderChunk(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        runtime.presenter().purgeOrphans(event.getEntities(), token -> runtime.cache().isActiveToken(token));
    }
}
