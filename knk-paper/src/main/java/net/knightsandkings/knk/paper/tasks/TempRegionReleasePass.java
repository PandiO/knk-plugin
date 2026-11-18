package net.knightsandkings.knk.paper.tasks;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.knightsandkings.knk.core.ports.api.TempRegionReleasesApi;
import net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy;
import net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy.ReleaseDecision;
import net.knightsandkings.knk.core.regions.TempRegionCleanupPolicy.Usage;
import net.knightsandkings.knk.core.regions.TempRegionRelease;
import net.knightsandkings.knk.paper.regions.RegionBookkeepingFlags;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * PandiO/knk-workspace#269: removes the temp regions knk-web-api released (their draft was deleted, their task closed
 * without a result, or the world-task sweep removed their session) without waiting for the 14-day cleanup. One pass:
 * list the due releases, decide each one with {@link TempRegionCleanupPolicy#decideRelease} (the same "used by a domain or
 * lootbox area" lookup as the cleanup, no age check), remove what may go, and settle what is decided. A release that has
 * to wait (task still running here, repair not run, API not reachable) is asked again on the next pass; the 14-day
 * cleanup stays the backstop for everything. Blocks on the API; run it off the main thread.
 */
public class TempRegionReleasePass {
    private static final Logger LOGGER = Logger.getLogger(TempRegionReleasePass.class.getName());
    private static final long API_TIMEOUT_SECONDS = 15;

    /** The WorldGuard side, so the pass can be tested without a server. */
    public interface RegionStore {
        /** The loaded worlds that hold a region with this id: only {@code preferredWorld} when it is loaded, else all of them. */
        List<String> worldsHolding(String regionId, String preferredWorld);

        /** A bookkeeping flag of the region in that world, or null. */
        String flag(String world, String regionId, String flagName);

        /** Removes the region from that world (its children stay, without a parent). */
        void remove(String world, String regionId) throws Exception;
    }

    /** What one pass did; names are {@code world/region} (or the region alone when it was found nowhere). */
    public record Result(List<String> removed, List<String> missing, List<String> kept, List<String> waiting, List<String> failed) {
        public boolean any() {
            return !removed.isEmpty() || !missing.isEmpty() || !kept.isEmpty() || !failed.isEmpty();
        }
    }

    private final TempRegionReleasesApi api;
    private final String serverId;
    private final RegionStore store;
    private final Predicate<String> knownDomainRegion;
    private final Function<String, Usage> freshUsage;
    private final IntPredicate taskActiveHere;

    /**
     * @param knownDomainRegion as for {@link TempRegionRetentionTask}: true for everything until the repair has run
     * @param freshUsage        as for {@link TempRegionRetentionTask#regionUsageVia}
     * @param taskActiveHere    whether a player on this server is running that world task
     */
    public TempRegionReleasePass(TempRegionReleasesApi api, String serverId, RegionStore store,
                                 Predicate<String> knownDomainRegion, Function<String, Usage> freshUsage,
                                 IntPredicate taskActiveHere) {
        this.api = api;
        this.serverId = serverId;
        this.store = store;
        this.knownDomainRegion = knownDomainRegion != null ? knownDomainRegion : regionId -> true;
        this.freshUsage = freshUsage != null ? freshUsage : regionId -> Usage.UNKNOWN;
        this.taskActiveHere = taskActiveHere != null ? taskActiveHere : taskId -> false;
    }

    /** One pass; a failure to list ends it with nothing done (logged), a failure on one release skips that release. */
    public Result run() {
        Result result = new Result(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        List<TempRegionRelease> due;
        try {
            due = api.listDue(serverId).get(API_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result;
        } catch (Exception e) {
            LOGGER.fine("Could not list released temp regions: " + rootMessage(e));
            return result;
        }
        for (TempRegionRelease release : due == null ? List.<TempRegionRelease>of() : due) {
            try {
                handle(release, result);
            } catch (RuntimeException e) {
                result.failed().add(String.valueOf(release.regionId()));
                LOGGER.warning("Released temp region " + release.regionId() + " was skipped: " + e.getMessage());
            }
        }
        return result;
    }

    private void handle(TempRegionRelease release, Result result) {
        String regionId = release.regionId() == null ? "" : release.regionId().trim();
        List<String> worlds = regionId.isEmpty() ? List.of() : store.worldsHolding(regionId, release.world());
        String world = worlds.size() == 1 ? worlds.get(0) : null;
        String timestamp = world == null ? null : store.flag(world, regionId, RegionBookkeepingFlags.CREATION_TIMESTAMP);
        ReleaseDecision decision = TempRegionCleanupPolicy.decideRelease(release, worlds.size(), timestamp,
                taskActiveHere.test(release.worldTaskId()), knownDomainRegion, freshUsage);
        String name = world == null ? regionId : world + "/" + regionId;

        if (!decision.settles()) {
            result.waiting().add(name);
            return;
        }
        TempRegionRelease.Outcome outcome;
        if (decision.remove()) {
            try {
                store.remove(world, regionId);
            } catch (Exception e) {
                result.failed().add(name);
                LOGGER.warning("Failed to remove released temp region " + name + ": " + e.getMessage());
                return;
            }
            result.removed().add(name);
            LOGGER.info("Removed temp region " + name + " (" + describe(release.reason()) + "; no domain or lootbox area uses it)");
            outcome = TempRegionRelease.Outcome.REMOVED;
        } else if (decision == ReleaseDecision.MISSING) {
            result.missing().add(name);
            outcome = TempRegionRelease.Outcome.MISSING;
        } else {
            result.kept().add(name);
            LOGGER.info("Kept released temp region " + name + ": " + decision.name().toLowerCase(Locale.ROOT).replace('_', ' '));
            outcome = TempRegionRelease.Outcome.KEPT;
        }
        settle(release, outcome, decision.name());
    }

    private void settle(TempRegionRelease release, TempRegionRelease.Outcome outcome, String detail) {
        try {
            api.settle(release.id(), outcome, serverId, detail).get(API_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Listed again next pass: a removed region is then missing, a kept one is kept again.
            LOGGER.fine("Could not settle temp region release " + release.id() + ": " + rootMessage(e));
        }
    }

    private static String describe(String reason) {
        if (reason == null) return "released";
        return switch (reason) {
            case "DraftDeleted" -> "its draft was deleted";
            case "TaskCancelled" -> "its task was cancelled";
            case "TaskExpired" -> "its task expired";
            case "TaskFailed" -> "its task failed";
            case "SessionRemoved" -> "its workflow session was removed";
            default -> reason;
        };
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    /** The live WorldGuard store over the server's loaded worlds. */
    public static RegionStore worldGuard(Plugin plugin) {
        return new RegionStore() {
            @Override
            public List<String> worldsHolding(String regionId, String preferredWorld) {
                List<World> worlds = new ArrayList<>();
                World preferred = preferredWorld == null ? null : plugin.getServer().getWorld(preferredWorld);
                if (preferred != null) {
                    worlds.add(preferred);
                } else {
                    worlds.addAll(plugin.getServer().getWorlds());
                }
                List<String> holding = new ArrayList<>();
                for (World world : worlds) {
                    RegionManager manager = manager(world);
                    if (manager != null && manager.getRegion(regionId) != null) {
                        holding.add(world.getName());
                    }
                }
                return holding;
            }

            @Override
            public String flag(String world, String regionId, String flagName) {
                ProtectedRegion region = region(world, regionId);
                return region == null ? null : RegionBookkeepingFlags.get(region, flagName);
            }

            @Override
            public void remove(String world, String regionId) {
                World bukkitWorld = plugin.getServer().getWorld(world);
                RegionManager manager = bukkitWorld == null ? null : manager(bukkitWorld);
                if (manager == null) {
                    throw new IllegalStateException("world " + world + " has no region manager");
                }
                manager.removeRegion(regionId);
            }

            private ProtectedRegion region(String world, String regionId) {
                World bukkitWorld = plugin.getServer().getWorld(world);
                RegionManager manager = bukkitWorld == null ? null : manager(bukkitWorld);
                return manager == null ? null : manager.getRegion(regionId);
            }

            private RegionManager manager(World world) {
                return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
            }
        };
    }
}
