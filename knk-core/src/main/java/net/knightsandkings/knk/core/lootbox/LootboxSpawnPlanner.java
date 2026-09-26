package net.knightsandkings.knk.core.lootbox;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoubleSupplier;

/**
 * The pure part of spawning (docs/specs/lootboxes/DESIGN.md §3.4, the siege {@code EnchantDropPlanner} pattern): per
 * area per scheduler tick, is it time to try, and if so where. The plugin then checks the spot physically (surface,
 * regions, player distance) and the API decides whether a box may really appear and what it is.
 * <p>
 * An area is tried when it is enabled, its interval has passed since the last try, enough players are online, it is
 * below its own and the global cap (as far as the local cache knows), and the chance roll hits. A try costs the
 * interval whether or not a box appears, so a full or unlucky area isn't retried every tick.
 */
public final class LootboxSpawnPlanner {

    /** A region's horizontal bounding box (block coordinates, inclusive). */
    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean containsXZ(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /** A point to test: uniform over the area's bounding box. */
    public record Candidate(int x, int z) {
    }

    /** Why an area is not tried this tick (for {@code /knk lootbox area info} and tests). */
    public enum Skip {
        NONE, DISABLED, INTERVAL, TOO_FEW_PLAYERS, AREA_FULL, GLOBAL_FULL, CHANCE
    }

    /** The decision for one area: {@link Skip#NONE} means "try now". */
    public record Decision(Skip skip) {
        public boolean shouldTry() {
            return skip == Skip.NONE;
        }
    }

    private final DoubleSupplier random;
    private final Map<Integer, Instant> lastTry = new HashMap<>();

    /** @param random uniform in [0, 1) */
    public LootboxSpawnPlanner(DoubleSupplier random) {
        this.random = random;
    }

    /**
     * Decides for {@code area} at {@code now}. A positive decision records the try, so the next one waits the area's
     * interval. The chance roll only happens when every other rule passes.
     */
    public synchronized Decision decide(
            KnkLootboxArea area,
            boolean globallyEnabled,
            Instant now,
            int onlinePlayers,
            int activeInArea,
            int activeGlobal,
            int globalMaxActive
    ) {
        if (!globallyEnabled || area == null || !area.enabled()) {
            return new Decision(Skip.DISABLED);
        }
        Instant last = lastTry.get(area.id());
        Duration interval = Duration.ofSeconds(Math.max(1, area.spawnIntervalSeconds()));
        if (last != null && now.isBefore(last.plus(interval))) {
            return new Decision(Skip.INTERVAL);
        }
        if (onlinePlayers < area.minOnlinePlayers()) {
            return new Decision(Skip.TOO_FEW_PLAYERS);
        }
        if (activeInArea >= area.maxActive()) {
            return new Decision(Skip.AREA_FULL);
        }
        if (activeGlobal >= globalMaxActive) {
            return new Decision(Skip.GLOBAL_FULL);
        }
        lastTry.put(area.id(), now);
        double chance = Math.max(0, Math.min(100, area.spawnChancePercent())) / 100.0;
        if (random.getAsDouble() >= chance) {
            return new Decision(Skip.CHANCE);
        }
        return new Decision(Skip.NONE);
    }

    /** A point uniform over {@code bounds}' x/z; empty for a degenerate box. */
    public Optional<Candidate> candidate(Bounds bounds) {
        if (bounds == null || bounds.maxX() < bounds.minX() || bounds.maxZ() < bounds.minZ()) {
            return Optional.empty();
        }
        int x = bounds.minX() + (int) Math.floor(random.getAsDouble() * (bounds.maxX() - bounds.minX() + 1));
        int z = bounds.minZ() + (int) Math.floor(random.getAsDouble() * (bounds.maxZ() - bounds.minZ() + 1));
        return Optional.of(new Candidate(Math.min(x, bounds.maxX()), Math.min(z, bounds.maxZ())));
    }

    /** Forgets an area's last try (deleted area, or {@code /knk lootbox reload}). */
    public synchronized void forget(int areaId) {
        lastTry.remove(areaId);
    }

    public synchronized void reset() {
        lastTry.clear();
    }

    /** Whether {@code (x, z)} is at least {@code minDistance} blocks (horizontally and vertically) from a player. */
    public static boolean farEnough(double dx, double dy, double dz, int minDistance) {
        return dx * dx + dy * dy + dz * dz >= (double) minDistance * minDistance;
    }
}
