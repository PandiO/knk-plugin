package net.knightsandkings.knk.paper.roads;

import java.util.Objects;
import java.util.function.Predicate;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

import net.knightsandkings.knk.core.roads.build.GateCells;
import net.knightsandkings.knk.core.roads.build.PassabilityRules;

/**
 * {@link CompactSurfaceGrid} fed from Bukkit chunk snapshots (DESIGN §5.4, §9; plan Phase 3
 * "ChunkSnapshotSurfaceGrid"): {@link #capture(ChunkSnapshot, int, int)} runs on the main thread with a
 * {@code chunk.getChunkSnapshot(false, false, false)}, reduces it at once to compact spans and lets the
 * snapshot go - never keeps a {@code ChunkSnapshot}. {@link #bukkitCollidable()} is the live server's
 * collision predicate for the passability rules.
 */
public final class ChunkSnapshotSurfaceGrid extends CompactSurfaceGrid {

    public ChunkSnapshotSurfaceGrid(PassabilityRules rules, Predicate<String> roadFloor, GateCells gates, int minY, int maxY) {
        super(rules, roadFloor, gates, minY, maxY);
    }

    /** Bukkit's collision flag by material name; unknown names fall back to the curated list. */
    public static Predicate<String> bukkitCollidable() {
        return name -> {
            Material material = Material.matchMaterial(name);
            if (material == null) {
                return PassabilityRules.curatedCollidable(name);
            }
            try {
                return material.isCollidable();
            } catch (RuntimeException | LinkageError e) {
                return material.isSolid();
            }
        };
    }

    /** Extracts one chunk snapshot (main thread); returns the spans found. The snapshot is not retained. */
    public int capture(ChunkSnapshot snapshot, int chunkX, int chunkZ) {
        Objects.requireNonNull(snapshot, "snapshot");
        return capture((lx, y, lz) -> snapshot.getBlockType(lx, y, lz).name(), chunkX, chunkZ);
    }
}
