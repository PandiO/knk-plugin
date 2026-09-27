package net.knightsandkings.knk.paper.discovery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import net.knightsandkings.knk.core.discovery.DiscoveryRecorder;
import net.knightsandkings.knk.core.discovery.DiscoveryTracker;
import net.knightsandkings.knk.core.discovery.PendingDiscovery;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;

/**
 * Sends the players' pending discoveries and replays the spool (docs/specs/domain-discovery
 * DESIGN.md §3.6):
 * <ul>
 *   <li>every {@code batch-window-ticks}: one grant request per player with due candidates and
 *   nothing in flight ({@link DiscoveryTracker#nextBatch}); the answer updates the tracker and plays
 *   the effects, a spooled or refused request is not sent again this session;</li>
 *   <li>every {@code replay-interval-seconds}, on enable and on a player's next join: the spool is
 *   replayed. A delivered replay shows full effects when the discovery was made under 30 s ago,
 *   otherwise one summary; nothing for a player who is offline (the menu shows it).</li>
 *   <li>players tracked without a knk user id (they joined while the API was down) get it from the
 *   recorder's lookup by UUID - on a grant, a replay, or the replay timer's lookup for every such
 *   player - and their session is upgraded ({@link DiscoveryTracker#resolveUser}), then the listener
 *   loads their known set ({@link #setUserResolvedHandler}). So discoveries resume without a rejoin
 *   once the API is back.</li>
 * </ul>
 * Timers run on the main thread; API calls and spool file I/O run off it.
 */
public final class DiscoveryFlushTask {
    private static final Logger LOGGER = Logger.getLogger(DiscoveryFlushTask.class.getName());
    /** A replayed discovery younger than this still gets the full moment rather than a summary. */
    static final Duration FULL_EFFECTS_WINDOW = Duration.ofSeconds(30);

    private final Plugin plugin;
    private final DiscoveryTracker tracker;
    private final DiscoveryRecorder recorder;
    private final DiscoveryEffects effects;
    private final Clock clock;
    private final long batchWindowTicks;
    private final long replayIntervalTicks;
    private BukkitTask flushTimer;
    private BukkitTask replayTimer;
    private volatile BiConsumer<UUID, Integer> userResolvedHandler;

    public DiscoveryFlushTask(Plugin plugin, DiscoveryTracker tracker, DiscoveryRecorder recorder, DiscoveryEffects effects,
                              Clock clock, int batchWindowTicks, int replayIntervalSeconds) {
        this.plugin = plugin;
        this.tracker = tracker;
        this.recorder = recorder;
        this.effects = effects;
        this.clock = clock;
        this.batchWindowTicks = Math.max(1, batchWindowTicks);
        this.replayIntervalTicks = Math.max(10, replayIntervalSeconds) * 20L;
    }

    /** Starts both timers and replays whatever the last run left in the spool. */
    public void start() {
        flushTimer = Bukkit.getScheduler().runTaskTimer(plugin, this::flush, batchWindowTicks, batchWindowTicks);
        replayTimer = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            replayAll();
            resolveUnresolved();
        }, replayIntervalTicks, replayIntervalTicks);
        replayAll();
    }

    /** Runs (main thread) when a player tracked without a user id got one: e.g. load their known set. */
    public void setUserResolvedHandler(BiConsumer<UUID, Integer> handler) {
        this.userResolvedHandler = handler;
    }

    public void stop() {
        if (flushTimer != null) {
            flushTimer.cancel();
            flushTimer = null;
        }
        if (replayTimer != null) {
            replayTimer.cancel();
            replayTimer = null;
        }
    }

    /**
     * Shutdown: spools every player's unsent candidates and the grants still in flight, so nothing
     * discovered in this run is lost. Call after {@link #stop()}.
     */
    public void spoolEverything() {
        for (Map.Entry<UUID, Map.Entry<Integer, List<PendingDiscovery>>> entry : tracker.endAll().entrySet()) {
            recorder.spoolPending(entry.getKey(), entry.getValue().getKey(), entry.getValue().getValue());
        }
        recorder.spoolInFlight();
    }

    /** Main thread. */
    void flush() {
        Instant now = clock.instant();
        for (UUID playerId : tracker.playersWithPending()) {
            tracker.nextBatch(playerId, now).ifPresent(this::send);
        }
    }

    private void send(DiscoveryTracker.Batch batch) {
        LOGGER.fine("[Discovery] Sending " + batch.entries().size() + " candidate(s) of user " + batch.userId()
                + " (" + batch.source().apiName() + "): " + batch.regionIds());
        recorder.grant(batch.playerId(), batch.userId(), batch.entries(), batch.source())
                .thenAccept(outcome -> runOnMainThread(() -> handle(batch, outcome)));
    }

    /** Main thread. */
    private void handle(DiscoveryTracker.Batch batch, DiscoveryRecorder.Outcome outcome) {
        // A batch of a player tracked without a user id: the recorder looked it up by UUID.
        int userId = batch.userResolved() ? batch.userId() : outcome.userId();
        if (!batch.userResolved() && userId > 0) {
            userResolved(batch.playerId(), userId);
        }
        if (outcome.status() != DiscoveryRecorder.Status.DELIVERED) {
            tracker.deferred(batch);
            return;
        }
        DiscoveryGrantResult result = outcome.result();
        tracker.completed(batch, result, clock.instant());
        if (!result.hasGrants()) {
            return;
        }
        LOGGER.fine("[Discovery] User " + userId + " discovered " + result.granted().size() + " place(s): +"
                + result.totalCoins() + " coins, +" + result.totalGems() + " gems, +" + result.totalExp() + " XP");
        Player player = Bukkit.getPlayer(batch.playerId());
        if (player != null && tracker.userId(batch.playerId()).orElse(-1) == userId) {
            effects.show(player, result);
        }
    }

    /** Replays the whole spool (off the main thread; skipped while it is empty). */
    public void replayAll() {
        replayAsync(() -> recorder.spool().isEmpty() ? CompletableFuture.completedFuture(List.of()) : recorder.replay());
    }

    /** Replays one player's spooled discoveries (their join). */
    public void replayFor(UUID playerId) {
        replayAsync(() -> recorder.replay(playerId));
    }

    private void replayAsync(Supplier<CompletableFuture<List<DiscoveryRecorder.Replayed>>> replay) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> replay.get()
                .thenAccept(replayed -> {
                    if (!replayed.isEmpty()) {
                        runOnMainThread(() -> handleReplayed(replayed));
                    }
                }));
    }

    /** Main thread. */
    private void handleReplayed(List<DiscoveryRecorder.Replayed> replayed) {
        Instant now = clock.instant();
        for (DiscoveryRecorder.Replayed delivery : replayed) {
            // A file spooled without a user id: the replay looked it up, so an online player's
            // unresolved session takes it over before the answer is applied.
            userResolved(delivery.playerId(), delivery.userId());
            tracker.replayed(delivery.playerId(), delivery.userId(), delivery.entries(), delivery.result(), now);
            Player player = Bukkit.getPlayer(delivery.playerId());
            if (player == null || !delivery.result().hasGrants()) {
                continue;
            }
            Instant oldest = delivery.entries().stream().map(PendingDiscovery::discoveredAt).min(Instant::compareTo).orElse(Instant.EPOCH);
            if (isRecent(oldest, now)) {
                effects.show(player, delivery.result());
            } else {
                effects.showSummary(player, delivery.result());
            }
        }
    }

    /**
     * Replay timer: looks up the user id of every player still tracked without one, so their session
     * upgrades as soon as the API answers again, even when they found nothing new meanwhile.
     */
    void resolveUnresolved() {
        for (UUID playerId : tracker.unresolvedPlayers()) {
            recorder.lookUpUserId(playerId).thenAccept(found ->
                    found.ifPresent(userId -> runOnMainThread(() -> userResolved(playerId, userId))));
        }
    }

    /** Main thread. Upgrades an unresolved session; a no-op when it already has a user id. */
    private void userResolved(UUID playerId, int userId) {
        if (!tracker.resolveUser(playerId, userId)) {
            return;
        }
        LOGGER.info("[Discovery] Player " + playerId + " now has user id " + userId + "; their discoveries resume");
        BiConsumer<UUID, Integer> handler = userResolvedHandler;
        if (handler != null) {
            handler.accept(playerId, userId);
        }
    }

    static boolean isRecent(Instant discoveredAt, Instant now) {
        return Duration.between(discoveredAt, now).compareTo(FULL_EFFECTS_WINDOW) < 0;
    }

    private void runOnMainThread(Runnable runnable) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }
}
