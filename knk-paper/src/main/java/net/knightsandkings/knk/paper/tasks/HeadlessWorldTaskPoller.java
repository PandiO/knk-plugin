package net.knightsandkings.knk.paper.tasks;

import net.knightsandkings.knk.api.dto.ClaimTaskDto;
import net.knightsandkings.knk.api.dto.WorldTaskDto;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Periodically polls the Web API for Pending WorldTasks that don't require a player
 * and dispatches each to the first registered handler that supports its TaskType.
 * Runs no scanning/game logic itself; each handler owns its own thread-safety.
 *
 * <p>PandiO/knk-workspace#180: a task is claimed (claim mode "headless") before its handler runs, so a second game server
 * polling the same API gets a 409 and skips it instead of running the scan twice, and the claim is heartbeated while the
 * scan runs (LeaseTrackingWorldTasksApi).
 *
 * TODO: Replace this pull-based polling loop with a push notification from the API (e.g. a
 * SignalR hub the plugin subscribes to) that fires the moment a headless WorldTask is created,
 * instead of the plugin discovering it up to headless-poll-max-interval-seconds later. The
 * idle back-off in {@link #onPollFinished} exists specifically to bound how often an otherwise-
 * unnecessary "any pending tasks?" poll fires while idle - a server-initiated notification would
 * remove the need for that trade-off entirely (no polling latency AND no idle API load).
 */
public class HeadlessWorldTaskPoller {
    private static final Logger LOGGER = Logger.getLogger(HeadlessWorldTaskPoller.class.getName());
    private static final long DEFAULT_MIN_INTERVAL_SECONDS = 5L;
    private static final long DEFAULT_MAX_INTERVAL_SECONDS = 60L;

    private final WorldTasksApi worldTasksApi;
    private final Plugin plugin;
    private final String serverId;
    private final long minIntervalTicks;
    private final long maxIntervalTicks;
    private final List<IHeadlessWorldTaskHandler> handlers = new ArrayList<>();
    private final Set<Integer> inFlightTaskIds = ConcurrentHashMap.newKeySet();

    private volatile long currentIntervalTicks;
    private volatile boolean running;
    private volatile java.util.function.BooleanSupplier hubConnected = () -> false;
    private org.bukkit.scheduler.BukkitTask task;

    public HeadlessWorldTaskPoller(WorldTasksApi worldTasksApi, Plugin plugin, String serverId) {
        this(worldTasksApi, plugin,
            plugin.getConfig().getLong("world-tasks.headless-poll-interval-seconds", DEFAULT_MIN_INTERVAL_SECONDS),
            plugin.getConfig().getLong("world-tasks.headless-poll-max-interval-seconds", DEFAULT_MAX_INTERVAL_SECONDS),
            serverId);
    }

    public HeadlessWorldTaskPoller(WorldTasksApi worldTasksApi, Plugin plugin,
                                   long minIntervalSeconds, long maxIntervalSeconds, String serverId) {
        this.worldTasksApi = worldTasksApi;
        this.plugin = plugin;
        this.serverId = serverId;
        this.minIntervalTicks = Math.max(1L, minIntervalSeconds) * 20L;
        this.maxIntervalTicks = Math.max(this.minIntervalTicks, Math.max(1L, maxIntervalSeconds) * 20L);
        this.currentIntervalTicks = this.minIntervalTicks;
    }

    /**
     * PandiO/knk-workspace#181 (DESIGN §8.4): while the realtime channel is connected, {@code WorldTaskCreated} starts a
     * scan at once ({@link #pollNow()}) and this poll is only the safety net, at the idle interval (60 s).
     */
    public void setHubConnected(java.util.function.BooleanSupplier connected) {
        this.hubConnected = connected != null ? connected : () -> false;
    }

    /** #181: one poll right away (a {@code WorldTaskCreated} arrived), next to the scheduled ones. */
    public void pollNow() {
        if (!running) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, (Runnable) () -> pollOnce(false));
    }

    public void registerHandler(IHeadlessWorldTaskHandler handler) {
        handlers.add(handler);
    }

    public void start() {
        if (handlers.isEmpty()) {
            LOGGER.info("HeadlessWorldTaskPoller not started: no headless handlers registered");
            return;
        }
        running = true;
        scheduleNextPoll(currentIntervalTicks);
        LOGGER.info("HeadlessWorldTaskPoller started (interval=" + (minIntervalTicks / 20L)
            + "s, backing off to " + (maxIntervalTicks / 20L) + "s while idle)");
    }

    public void stop() {
        running = false;
        if (task != null) {
            task.cancel();
        }
    }

    private void scheduleNextPoll(long delayTicks) {
        if (!running) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> pollOnce(), delayTicks);
    }

    void pollOnce() {
        pollOnce(true);
    }

    /** @param scheduled false for {@link #pollNow()}: it doesn't schedule the next poll (the scheduled chain goes on). */
    void pollOnce(boolean scheduled) {
        worldTasksApi.listByStatus("Pending").thenAccept(tasks -> {
            boolean dispatchedAny = false;
            for (WorldTaskDto candidate : tasks) {
                dispatchedAny |= dispatchIfSupported(candidate);
            }
            if (scheduled) {
                onPollFinished(dispatchedAny);
            }
        }).exceptionally(ex -> {
            LOGGER.warning("HeadlessWorldTaskPoller failed to list pending tasks: " + ex.getMessage());
            if (scheduled) {
                onPollFinished(false);
            }
            return null;
        });
    }

    /**
     * Polls fast while there is headless work, and backs off exponentially while idle; #181: while the hub is connected the
     * safety-net poll stays at the idle interval (pushes start the scans).
     */
    private void onPollFinished(boolean dispatchedAny) {
        currentIntervalTicks = nextInterval(dispatchedAny, hubConnected.getAsBoolean());
        scheduleNextPoll(currentIntervalTicks);
    }

    long nextInterval(boolean dispatchedAny, boolean connected) {
        if (connected) {
            return maxIntervalTicks;
        }
        return dispatchedAny ? minIntervalTicks : Math.min(maxIntervalTicks, currentIntervalTicks * 2L);
    }

    private boolean dispatchIfSupported(WorldTaskDto candidate) {
        if (candidate == null || candidate.id() == null) {
            return false;
        }

        IHeadlessWorldTaskHandler handler = findHandler(candidate.taskType());
        if (handler == null) {
            return false;
        }

        if (!inFlightTaskIds.add(candidate.id())) {
            return true; // already being processed from a previous poll cycle
        }

        // #180: claim first; only the server whose claim succeeds runs the scan.
        worldTasksApi.claim(candidate.id(), ClaimTaskDto.headless(serverId)).thenAccept(claimed -> {
            LOGGER.info("Dispatching headless task " + candidate.id() + " (" + candidate.taskType() + ")");
            try {
                handler.execute(claimed != null ? claimed : candidate, () -> inFlightTaskIds.remove(candidate.id()));
            } catch (Exception e) {
                LOGGER.warning("Headless handler threw for task " + candidate.id() + ": " + e.getMessage());
                inFlightTaskIds.remove(candidate.id());
                worldTasksApi.fail(candidate.id(), "The game server's scan handler failed: " + e.getMessage())
                    .exceptionally(ignored -> null);
            }
        }).exceptionally(ex -> {
            inFlightTaskIds.remove(candidate.id());
            if (WorldTaskApiErrors.status(ex) == 409) {
                LOGGER.fine("Headless task " + candidate.id() + " is taken (another server, or no longer Pending)");
            } else {
                LOGGER.warning("Claiming headless task " + candidate.id() + " failed: " + ex.getMessage());
            }
            return null;
        });
        return true;
    }

    private IHeadlessWorldTaskHandler findHandler(String taskType) {
        for (IHeadlessWorldTaskHandler handler : handlers) {
            if (handler.supports(taskType)) {
                return handler;
            }
        }
        return null;
    }
}
