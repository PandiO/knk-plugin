package net.knightsandkings.knk.paper.tasks;

import net.knightsandkings.knk.api.dto.WorldTaskDto;
import net.knightsandkings.knk.core.ports.api.WorldTasksApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * PandiO/knk-workspace#180 (DESIGN §8.2, plugin hygiene): the world tasks this server holds a claim on, and the calls that
 * keep the API's view right.
 * <ul>
 *   <li>a successful claim is recorded ({@link #hold}); a completion or failure forgets it ({@link #forget});</li>
 *   <li>{@link #heartbeatAll()} renews every claim (every 60 s; the API releases a claim after 3 minutes without one). A
 *       409/404 means the task is no longer ours (cancelled in the web app, released by the sweep, deleted): it is
 *       forgotten and {@code onLost} stops the player's handler;</li>
 *   <li>in-game {@code cancel} → {@link #handedBack} (release), {@code discard} → {@link #discarded} (cancel);</li>
 *   <li>quit → {@link #releaseHeldBy}; plugin disable → {@link #releaseAll}; startup → {@link #releaseOrphans} hands back
 *       what the API still has this server holding (a restart lost the handlers).</li>
 * </ul>
 * Bukkit-free: the caller decides on which thread {@code onLost} runs.
 */
public class WorldTaskLeases implements WorldTaskLifecycle {
    private static final Logger LOGGER = Logger.getLogger(WorldTaskLeases.class.getName());

    /** One claim: the player (null for a headless task) and the task type. */
    public record Held(int taskId, UUID playerId, String taskType) {
    }

    private final WorldTasksApi api;
    private final String serverId;
    private final Map<Integer, Held> held = new ConcurrentHashMap<>();
    private volatile BiConsumer<Held, String> onLost = (task, status) -> { };

    /**
     * @param api      the plain API client (not the tracking wrapper)
     * @param serverId {@code realtime.server-id}
     */
    public WorldTaskLeases(WorldTasksApi api, String serverId) {
        this.api = api;
        this.serverId = serverId;
    }

    /** Called with the lost claim and the task's status from the API (e.g. "Cancelled"), or null when it is unknown. */
    public void setOnLost(BiConsumer<Held, String> onLost) {
        this.onLost = onLost != null ? onLost : (task, status) -> { };
    }

    public String serverId() {
        return serverId;
    }

    public void hold(int taskId, UUID playerId, String taskType) {
        held.put(taskId, new Held(taskId, playerId, taskType));
    }

    public void forget(int taskId) {
        held.remove(taskId);
    }

    public boolean holds(int taskId) {
        return held.containsKey(taskId);
    }

    /**
     * #181: forgets one claim the API took back and says which it was (the hub's {@code WorldTaskCancelled}: cancelled in
     * the web app, expired, or released by the web app's "Replace"). Empty when this server didn't hold it. The caller
     * stops the handler; {@code onLost} is not called.
     */
    public Optional<Held> take(int taskId) {
        return Optional.ofNullable(held.remove(taskId));
    }

    public List<Held> held() {
        return List.copyOf(held.values());
    }

    public List<Held> heldBy(UUID playerId) {
        List<Held> result = new ArrayList<>();
        for (Held task : held.values()) {
            if (playerId != null && playerId.equals(task.playerId())) {
                result.add(task);
            }
        }
        return result;
    }

    /** Renews every claim; claims the API no longer gives us are forgotten and reported to {@code onLost}. */
    public CompletableFuture<Void> heartbeatAll() {
        List<CompletableFuture<?>> calls = new ArrayList<>();
        for (Held task : held.values()) {
            calls.add(api.heartbeat(task.taskId(), serverId).handle((ok, error) -> {
                if (error != null) {
                    if (WorldTaskApiErrors.taskMovedOn(error)) {
                        lose(task, WorldTaskApiErrors.currentStatus(error).orElse(null));
                    } else {
                        // API down or slow: the claim stays; the next beat tries again (the lease is 3 heartbeats long).
                        LOGGER.fine("Heartbeat for task " + task.taskId() + " failed: " + error.getMessage());
                    }
                } else if (ok != null && ok.status() != null && !WorldTaskDto.IN_PROGRESS.equalsIgnoreCase(ok.status())) {
                    lose(task, ok.status());
                }
                return null;
            }));
        }
        return CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new));
    }

    private void lose(Held task, String status) {
        if (held.remove(task.taskId()) != null) {
            LOGGER.info("World task " + task.taskId() + " is no longer held by this server"
                + (status != null ? " (now " + status + ")" : ""));
            onLost.accept(task, status);
        }
    }

    @Override
    public void handedBack(org.bukkit.entity.Player player, int taskId) {
        // #181: on purpose, so the API doesn't offer it to them again and the web app shows the task code.
        release(taskId, "Handed back in game by " + player.getName(), true);
    }

    @Override
    public void discarded(org.bukkit.entity.Player player, int taskId) {
        held.remove(taskId);
        api.cancel(taskId, "Discarded in game by " + player.getName()).exceptionally(error -> {
            LOGGER.warning("Cancelling world task " + taskId + " failed: " + error.getMessage());
            return null;
        });
    }

    /** Hands one task back to Pending (it keeps its task code). */
    public CompletableFuture<Void> release(int taskId, String reason) {
        return release(taskId, reason, false);
    }

    /** @param handBack #181: the player handed it back on purpose (in-game {@code cancel}) */
    public CompletableFuture<Void> release(int taskId, String reason, boolean handBack) {
        held.remove(taskId);
        CompletableFuture<WorldTaskDto> call = handBack
            ? api.release(taskId, serverId, reason, true)
            : api.release(taskId, serverId, reason);
        return call.handle((ok, error) -> {
            if (error != null && !WorldTaskApiErrors.taskMovedOn(error)) {
                LOGGER.warning("Releasing world task " + taskId + " failed: " + error.getMessage()
                    + " (the API releases it once its lease runs out)");
            }
            return null;
        });
    }

    /** The player left: every claim they hold goes back to Pending. */
    public CompletableFuture<Void> releaseHeldBy(UUID playerId, String reason) {
        List<CompletableFuture<Void>> calls = new ArrayList<>();
        for (Held task : heldBy(playerId)) {
            calls.add(release(task.taskId(), reason));
        }
        return CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new));
    }

    /** Plugin disable: every claim goes back to Pending. */
    public CompletableFuture<Void> releaseAll(String reason) {
        List<CompletableFuture<Void>> calls = new ArrayList<>();
        for (Held task : held.values()) {
            calls.add(release(task.taskId(), reason));
        }
        return CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new));
    }

    /**
     * Startup resync: tasks the API says this server holds but no handler here knows (the server restarted mid-task) are
     * handed back, so they don't wait for the lease to run out.
     */
    public CompletableFuture<Integer> releaseOrphans() {
        return api.listOpenForServer(serverId).thenCompose(tasks -> {
            List<CompletableFuture<Void>> calls = new ArrayList<>();
            for (WorldTaskDto task : Optional.ofNullable(tasks).orElse(List.of())) {
                if (task.id() != null && !held.containsKey(task.id())) {
                    calls.add(release(task.id(), "The game server restarted"));
                }
            }
            return CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).thenApply(ignored -> calls.size());
        });
    }
}
