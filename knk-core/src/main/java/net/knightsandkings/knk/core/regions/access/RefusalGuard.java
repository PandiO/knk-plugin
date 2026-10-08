package net.knightsandkings.knk.core.regions.access;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player bookkeeping for refused region moves (KNG-56).
 *
 * <ul>
 *   <li><b>Message throttle</b> - like WorldGuard's entry/exit handlers, a player is told at most
 *       once per {@link Settings#messageIntervalMillis()}, however often they push the border.</li>
 *   <li><b>Load guard</b> - only a refusal <i>rate</i> no honest player produces (a vanilla client
 *       pushing a border gets a few refusals per second; packet spam gets far more) leads to an
 *       action: first a teleport to spawn, then - if it happens again within
 *       {@link Settings#escalationWindowMillis()} - a kick. Below that rate nothing happens besides
 *       the refusal itself (developer decision 2026-10-06: kick/spawn only under significant load).</li>
 * </ul>
 *
 * <p>Bukkit-free; the caller passes the clock. Thread-safe per player (main-thread use expected).
 */
public final class RefusalGuard {

    /** What the caller should do after a refusal. */
    public enum Action { NONE, TELEPORT_TO_SPAWN, KICK }

    /** One refusal's outcome: whether to show the message, and the load-guard action. */
    public record Outcome(boolean showMessage, Action action) {
    }

    /**
     * @param messageIntervalMillis    minimum time between two deny messages to one player
     * @param loadGuardEnabled         whether the load guard acts at all
     * @param maxRefusalsPerSecond     sustained refusal rate above which the load guard acts
     * @param windowMillis             how long the rate must be sustained
     * @param escalationWindowMillis   a second breach within this time after the teleport kicks
     */
    public record Settings(long messageIntervalMillis, boolean loadGuardEnabled, int maxRefusalsPerSecond,
                           long windowMillis, long escalationWindowMillis) {
        public static Settings defaults() {
            return new Settings(2000, true, 20, 3000, 60000);
        }

        int maxInWindow() {
            return (int) Math.max(1, Math.ceil(maxRefusalsPerSecond * (windowMillis / 1000.0)));
        }
    }

    private static final class PlayerState {
        long lastMessage = Long.MIN_VALUE;
        final Deque<Long> refusals = new ArrayDeque<>();
        long lastTeleport = Long.MIN_VALUE;
    }

    private final Settings settings;
    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();

    public RefusalGuard(Settings settings) {
        this.settings = settings != null ? settings : Settings.defaults();
    }

    public Settings settings() {
        return settings;
    }

    /** Record one refused move by {@code playerId} at {@code nowMillis}. */
    public Outcome onRefusal(UUID playerId, long nowMillis) {
        PlayerState state = players.computeIfAbsent(playerId, id -> new PlayerState());
        synchronized (state) {
            boolean notify = state.lastMessage == Long.MIN_VALUE
                || nowMillis - state.lastMessage >= settings.messageIntervalMillis();
            if (notify) {
                state.lastMessage = nowMillis;
            }
            if (!settings.loadGuardEnabled()) {
                return new Outcome(notify, Action.NONE);
            }

            state.refusals.addLast(nowMillis);
            while (!state.refusals.isEmpty() && nowMillis - state.refusals.peekFirst() >= settings.windowMillis()) {
                state.refusals.removeFirst();
            }
            if (state.refusals.size() <= settings.maxInWindow()) {
                return new Outcome(notify, Action.NONE);
            }

            state.refusals.clear();
            boolean recentlyTeleported = state.lastTeleport != Long.MIN_VALUE
                && nowMillis - state.lastTeleport < settings.escalationWindowMillis();
            if (recentlyTeleported) {
                state.lastTeleport = Long.MIN_VALUE;
                return new Outcome(true, Action.KICK);
            }
            state.lastTeleport = nowMillis;
            return new Outcome(true, Action.TELEPORT_TO_SPAWN);
        }
    }

    /** Forget a player (quit). */
    public void forget(UUID playerId) {
        players.remove(playerId);
    }
}
