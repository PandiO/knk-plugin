package net.knightsandkings.knk.paper.statistics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.statistics.StatisticsBatch;
import net.knightsandkings.knk.core.statistics.MovementClassifier;
import net.knightsandkings.knk.core.statistics.StatisticsBuffer;
import net.knightsandkings.knk.core.statistics.StatisticsContext;
import net.knightsandkings.knk.core.statistics.StatisticsMetric;
import net.knightsandkings.knk.core.statistics.StatisticsSessions;
import net.knightsandkings.knk.paper.config.KnkConfig;

/**
 * Player statistics in the running server (KNG-34, IMPLEMENTATION_PLAN.md §5.2): owns the buffer, the
 * sessions with their AFK trackers and the context resolution; the listeners, {@code /afk} and the
 * flush task call it. Everything here runs on the server main thread and does no I/O - batches are
 * drained here and sent by {@link StatisticsFlushTask} off the main thread.
 * <p>
 * Facts of a player in an excluded game mode ({@code statistics.excluded-game-modes}) are not
 * recorded except playtime; distance also stops while the player is AFK (DESIGN.md §F.2).
 */
public final class StatisticsService {

    private static final Logger LOGGER = Logger.getLogger(StatisticsService.class.getName());

    private final KnkConfig.StatisticsConfig config;
    private final StatisticsSessions sessions;
    private final StatisticsContextResolver contexts;
    private final AfkPresentation presentation;
    private final Clock clock;
    private final Set<UUID> kicked = new HashSet<>();
    private AfkObserver afkObserver = AfkObserver.NONE;

    /** Told about every AFK change (diagnostic telemetry, KNG-34 link 6); main thread; default no-op. */
    @FunctionalInterface
    public interface AfkObserver {
        AfkObserver NONE = (player, afk, manual) -> { };

        void afkChanged(Player player, boolean afk, boolean manual);
    }

    public StatisticsService(KnkConfig.StatisticsConfig config, StatisticsBuffer buffer, StatisticsContextResolver contexts,
                             AfkPresentation presentation, Clock clock) {
        this.config = config;
        this.contexts = contexts;
        this.presentation = presentation;
        this.clock = clock;
        Duration idle = config.afk().enabled() ? Duration.ofSeconds(config.afk().idleSeconds()) : Duration.ZERO;
        this.sessions = new StatisticsSessions(buffer, idle, UUID::randomUUID);
    }

    public KnkConfig.StatisticsConfig config() {
        return config;
    }

    public StatisticsBuffer buffer() {
        return sessions.buffer();
    }

    StatisticsSessions sessions() {
        return sessions;
    }

    public Instant now() {
        return clock.instant();
    }

    public void setAfkObserver(AfkObserver observer) {
        this.afkObserver = observer == null ? AfkObserver.NONE : observer;
    }

    /** The open session's key (diagnostic telemetry links its events to it); null without a session. */
    public UUID sessionKeyOf(UUID playerId) {
        return sessions.sessionKey(playerId);
    }

    private void notifyAfk(Player player, boolean afk, boolean manual) {
        try {
            afkObserver.afkChanged(player, afk, manual);
        } catch (RuntimeException e) {
            LOGGER.fine("[Statistics] AFK observer failed: " + e);
        }
    }

    // ===== sessions =====

    /** The player's account data loaded (a join); {@code userId} null when the API couldn't be reached. */
    public void sessionStarted(Player player, Integer userId) {
        kicked.remove(player.getUniqueId());
        sessions.start(player.getUniqueId(), userId == null ? 0 : userId, now());
    }

    /** Marks the coming quit as a kick (the kick event fires before the quit event). */
    public void kicked(UUID playerId) {
        kicked.add(playerId);
    }

    public void sessionEnded(Player player) {
        UUID playerId = player.getUniqueId();
        StatisticsBatch.EndReason reason = kicked.remove(playerId) ? StatisticsBatch.EndReason.Kick : StatisticsBatch.EndReason.Quit;
        sessions.end(playerId, reason, now());
        presentation.forget(playerId);
    }

    /** Server stop: every session ends with {@code ServerStop}; the tab-list names are put back. */
    public void endAll(Function<UUID, Player> online) {
        presentation.restoreAll(online);
        sessions.endAll(StatisticsBatch.EndReason.ServerStop, now());
    }

    /** Flush step: classified time and movement of every session go to the buffer. */
    public void accrueAll() {
        sessions.accrueAll(now());
    }

    public Set<UUID> unresolvedPlayers() {
        return sessions.unresolvedPlayers();
    }

    /** A player tracked without a user id got one (looked up by UUID). */
    public void resolved(UUID playerId, int userId) {
        if (sessions.resolve(playerId, userId)) {
            LOGGER.info("[Statistics] Player " + playerId + " now has user id " + userId + "; their statistics are sent");
        }
    }

    // ===== AFK =====

    public boolean afkEnabled() {
        return config.afk().enabled();
    }

    /** An activity signal (listeners). Ends AFK, with the message and marker removal. */
    public void activity(Player player) {
        if (sessions.activity(player.getUniqueId(), now())) {
            presentation.left(player);
            notifyAfk(player, false, false);
        }
    }

    /** {@code /afk}: the new state, empty when the player has no statistics session. */
    public Optional<Boolean> toggleAfk(Player player) {
        Optional<Boolean> afk = sessions.toggleAfk(player.getUniqueId(), now());
        afk.ifPresent(now -> {
            if (now) {
                presentation.entered(player, true);
            } else {
                presentation.left(player);
            }
            notifyAfk(player, now, true);
        });
        return afk;
    }

    /** Every second: players who crossed the idle threshold become AFK. */
    public void checkIdle(Function<UUID, Player> online) {
        if (!afkEnabled()) {
            return;
        }
        List<UUID> becameAfk = sessions.checkIdle(now());
        for (UUID playerId : becameAfk) {
            Player player = online.apply(playerId);
            if (player != null) {
                presentation.entered(player, false);
                notifyAfk(player, true, false);
            }
        }
    }

    public boolean isAfk(UUID playerId) {
        return sessions.isAfk(playerId);
    }

    // ===== facts =====

    /** Distance and falls are not recorded in an excluded game mode. */
    public boolean excluded(Player player) {
        GameMode mode = player.getGameMode();
        return mode != null && config.excludedGameModes().contains(mode);
    }

    /** One movement segment (per move event; no allocation). */
    public void addDistance(UUID playerId, MovementClassifier.Mode mode, double blocks) {
        sessions.addDistance(playerId, mode, blocks);
    }

    public void recordFall(Player player, double blocks) {
        sessions.addRecord(player.getUniqueId(), StatisticsMetric.HIGHEST_FALL, StatisticsContext.NONE, blocks, now());
    }

    /** A counter of the player in their current context (link 4's combat listeners use this). */
    public void addCounter(Player player, StatisticsMetric metric, double value) {
        sessions.addCounter(player.getUniqueId(), metric, contexts.contextOf(player), value, now());
    }

    /**
     * A counter of an online player in a given context - gate damage is credited in the gate's context
     * (DESIGN.md §F.8), not the attacker's. Held like any other fact while the user id is unknown.
     */
    public void addCounter(UUID playerId, StatisticsMetric metric, StatisticsContext context, double value) {
        sessions.addCounter(playerId, metric, context.forMetric(metric), value, now());
    }

    /** The user id of the player's statistics session; empty without a session or while unknown. */
    public java.util.OptionalInt userIdOf(UUID playerId) {
        return sessions.userId(playerId);
    }

    /** A record (max) of the player in their current context, e.g. link 4's {@code highest_killstreak}. */
    public void addRecord(Player player, StatisticsMetric metric, double value) {
        sessions.addRecord(player.getUniqueId(), metric, contexts.contextOf(player), value, now());
    }

    /**
     * A PvP kill (link 4): sent as a killer/victim pair in the killer's context. Needs both user ids;
     * a kill involving a player whose id isn't known yet is not recorded. Siege-context kills are
     * projected by the API from the match tables, so the caller skips running-match members (§F.6).
     */
    public boolean pvpKill(Player killer, Player victim) {
        java.util.OptionalInt killerId = sessions.userId(killer.getUniqueId());
        java.util.OptionalInt victimId = sessions.userId(victim.getUniqueId());
        if (killerId.isEmpty() || victimId.isEmpty() || killerId.getAsInt() == victimId.getAsInt()) {
            return false;
        }
        buffer().addPvpKill(new StatisticsBatch.PvpKillEntry(killerId.getAsInt(), victimId.getAsInt(),
                contexts.contextOf(killer).forMetric(StatisticsMetric.PVP_KILLS).key(), now()));
        return true;
    }

    public StatisticsContext contextOf(Player player) {
        return contexts.contextOf(player);
    }

    public boolean hasSession(UUID playerId) {
        return sessions.hasSession(playerId);
    }
}
