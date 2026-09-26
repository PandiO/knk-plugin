package net.knightsandkings.knk.paper.lootbox;

import net.knightsandkings.knk.core.lootbox.KnkLootboxArea;
import net.knightsandkings.knk.core.lootbox.KnkLootboxRuntimeConfig;
import net.knightsandkings.knk.core.lootbox.LootboxRejectedException;
import net.knightsandkings.knk.core.lootbox.LootboxSpawnPlanner;
import net.knightsandkings.knk.core.ports.api.LootboxesCommandApi;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Makes boxes appear (docs/specs/lootboxes/DESIGN.md §3.4). Every {@code scheduler-tick-seconds}, for each area the
 * {@link LootboxSpawnPlanner} says to try: up to {@code surface.max-attempts-per-tick} random columns in the area's
 * region bounds, each loaded with {@code getChunkAtAsync(x, z, false)}, which <b>never generates terrain</b> (an
 * ungenerated chunk is skipped). A column qualifies when its surface is solid, not a liquid, leaves or a forbidden
 * block, has two air blocks above it, lies inside the area's region and outside its excluded regions, and is at least
 * {@code MinDistanceFromPlayers} from every player. The first one that does is sent to the API, which checks the caps
 * again and rolls the type and box grade; the new box is cached, shown and (when grand enough) announced.
 */
public final class LootboxSpawnScheduler {

    private static final Logger LOGGER = Logger.getLogger(LootboxSpawnScheduler.class.getName());

    private final Plugin plugin;
    private final LootboxRuntime runtime;
    private final LootboxRegions regions;
    private final LootboxesCommandApi commandApi;
    private final LootboxAnnouncer announcer;
    private final LootboxSpawnPlanner planner;
    private final Set<Integer> inProgress = new HashSet<>();
    private BukkitTask task;

    public LootboxSpawnScheduler(Plugin plugin, LootboxRuntime runtime, LootboxRegions regions, LootboxesCommandApi commandApi,
                                 LootboxAnnouncer announcer, LootboxSpawnPlanner planner) {
        this.plugin = plugin;
        this.runtime = runtime;
        this.regions = regions;
        this.commandApi = commandApi;
        this.announcer = announcer;
        this.planner = planner;
    }

    public void start() {
        stop();
        long ticks = runtime.settings().schedulerTickSeconds() * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(), ticks, ticks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        inProgress.clear();
    }

    public LootboxSpawnPlanner planner() {
        return planner;
    }

    /** Main thread. */
    void tick() {
        LootboxSettings settings = runtime.settings();
        KnkLootboxRuntimeConfig config = runtime.config();
        if (!settings.enabled() || !config.enabled()) {
            return;
        }
        int online = Bukkit.getOnlinePlayers().size();
        for (KnkLootboxArea area : config.areas()) {
            if (inProgress.contains(area.id())) {
                continue;
            }
            World world = Bukkit.getWorld(area.world());
            if (world == null) {
                continue; // Another server's world, or not loaded here.
            }
            LootboxSpawnPlanner.Decision decision = planner.decide(area, config.enabled(), runtime.clock().instant(), online,
                    runtime.cache().countInArea(area.id()), runtime.cache().size(), config.globalMaxActive());
            if (!decision.shouldTry()) {
                continue;
            }
            Optional<LootboxSpawnPlanner.Bounds> bounds = regions.bounds(world, area.wgRegionId());
            if (bounds.isEmpty()) {
                LOGGER.fine("Lootbox area " + area.name() + ": region " + area.wgRegionId() + " not found in " + world.getName());
                continue;
            }
            inProgress.add(area.id());
            attempt(world, area, bounds.get(), settings.maxAttemptsPerTick());
        }
    }

    private void attempt(World world, KnkLootboxArea area, LootboxSpawnPlanner.Bounds bounds, int attemptsLeft) {
        Optional<LootboxSpawnPlanner.Candidate> candidate = planner.candidate(bounds);
        if (attemptsLeft <= 0 || candidate.isEmpty()) {
            inProgress.remove(area.id());
            return;
        }
        int x = candidate.get().x();
        int z = candidate.get().z();
        world.getChunkAtAsync(x >> 4, z >> 4, false).whenComplete((chunk, ex) -> runOnMainThread(() -> {
            Integer y = chunk == null || ex != null ? null : spawnY(world, area, x, z);
            if (y == null) {
                attempt(world, area, bounds, attemptsLeft - 1);
                return;
            }
            request(world, area, x, y, z);
        }));
    }

    /** The y a box would stand at in column (x, z), or null when the column doesn't qualify. Main thread. */
    Integer spawnY(World world, KnkLootboxArea area, int x, int z) {
        Block ground = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (!isGoodGround(ground, runtime.settings())) {
            return null;
        }
        Block feet = ground.getRelative(BlockFace.UP);
        Block head = feet.getRelative(BlockFace.UP);
        if (!feet.getType().isAir() || !head.getType().isAir()) {
            return null;
        }
        int y = feet.getY();
        if (!regions.contains(world, area.wgRegionId(), x, y, z)) {
            return null;
        }
        if (!area.excludedRegionIds().isEmpty()) {
            Set<String> here = regions.regionIdsAt(world, x, y, z);
            for (String excluded : area.excludedRegionIds()) {
                if (here.contains(excluded)) {
                    return null;
                }
            }
        }
        Location spot = new Location(world, x + 0.5, y, z + 0.5);
        for (Player player : world.getPlayers()) {
            Location at = player.getLocation();
            if (!LootboxSpawnPlanner.farEnough(at.getX() - spot.getX(), at.getY() - spot.getY(), at.getZ() - spot.getZ(),
                    area.minDistanceFromPlayers())) {
                return null;
            }
        }
        return y;
    }

    static boolean isGoodGround(Block ground, LootboxSettings settings) {
        Material type = ground.getType();
        if (ground.isLiquid() || !type.isSolid() || Tag.LEAVES.isTagged(type)) {
            return false;
        }
        return !settings.forbiddenGround().contains(type.name());
    }

    private void request(World world, KnkLootboxArea area, int x, int y, int z) {
        commandApi.spawn(area.id(), world.getName(), x, y, z, runtime.settings().serverId())
                .whenComplete((spawn, ex) -> runOnMainThread(() -> {
                    inProgress.remove(area.id());
                    if (ex != null) {
                        LootboxRejectedException rejected = LootboxRejectedException.find(ex);
                        if (rejected != null) {
                            LOGGER.fine("Lootbox area " + area.name() + ": no spawn (" + rejected.code() + ")");
                        } else {
                            LOGGER.log(Level.WARNING, "Lootbox area " + area.name() + ": spawn request failed: "
                                    + LootboxRejectedException.unwrap(ex).getMessage());
                        }
                        return;
                    }
                    runtime.added(spawn);
                    announcer.spawned(spawn, runtime.settings(), runtime.config());
                }));
    }

    private void runOnMainThread(Runnable runnable) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }
}
