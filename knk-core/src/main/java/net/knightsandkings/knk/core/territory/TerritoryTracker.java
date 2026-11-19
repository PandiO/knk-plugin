package net.knightsandkings.knk.core.territory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Where each player's territory is (territory DESIGN § 1: "the player's current territory is looked up on each chunk
 * crossing, per block inside split chunks"). {@link #moved} looks the cache up only when the player entered another chunk
 * or moves inside a split chunk to another column, and answers a {@link Change} when the area differs from the last one
 * (entry, exit, contested). Main thread on Paper; Bukkit-free.
 */
public final class TerritoryTracker {

    /** A player's area changed from {@code from} to {@code to}. */
    public record Change(UUID player, TerritoryVerdict from, TerritoryVerdict to, TerritoryCache cache) {
    }

    private record Position(TerritoryCache cache, String world, int chunkX, int chunkZ, int x, int z, boolean split,
                            TerritoryVerdict verdict) {
    }

    private final Supplier<TerritoryCache> cache;
    private final Map<UUID, Position> positions = new ConcurrentHashMap<>();

    public TerritoryTracker(Supplier<TerritoryCache> cache) {
        this.cache = cache;
    }

    /**
     * The player stands at block column ({@code x}, {@code z}) of {@code world}. A change when the area differs from the
     * last one this player was in; null otherwise, and null on the first call for a player (a join sets the start).
     */
    public Change moved(UUID player, String world, int x, int z) {
        TerritoryCache current = cache.get();
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        Position last = positions.get(player);
        if (last != null && last.cache == current && last.world.equals(world) && last.chunkX == chunkX
                && last.chunkZ == chunkZ && (!last.split || (last.x == x && last.z == z))) {
            return null;
        }
        TerritoryVerdict verdict = current.lookup(world, x, z);
        positions.put(player, new Position(current, world, chunkX, chunkZ, x, z, current.isSplit(world, chunkX, chunkZ),
                verdict));
        if (last == null || last.verdict.area().equals(verdict.area())) {
            return null;
        }
        return new Change(player, last.verdict, verdict, current);
    }

    /** The area the player was last seen in (wilderness when unknown). */
    public TerritoryVerdict current(UUID player) {
        Position position = positions.get(player);
        return position == null ? TerritoryVerdict.WILDERNESS : position.verdict;
    }

    public void forget(UUID player) {
        positions.remove(player);
    }

    public void clear() {
        positions.clear();
    }

    public int tracked() {
        return positions.size();
    }
}
