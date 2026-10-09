package net.knightsandkings.knk.core.regions.access;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player bookkeeping for refused region moves (KNG-56).
 *
 * <ul>
 *   <li><b>Message throttle</b> - like WorldGuard's entry/exit handlers, a player is told at most
 *       once per {@link Settings#messageIntervalMillis()}, however often they push the border.</li>
 *   <li><b>Chat once per episode</b> (KNG-74) - the throttled message goes to the action bar, which
 *       other HUDs (the navigation arrow) also use; the first shown message of a refusal
 *       <i>episode</i> also goes to chat so it cannot be missed. An episode starts with a refusal
 *       after at least {@link Settings#chatQuietPeriodMillis()} without any refusal, or with a
 *       refusal whose reason differs from the one last said in chat (another domain, entry vs exit).
 *       Chat follows the message throttle, so it is never sent more often than the action bar.</li>
 *   <li><b>Action-bar hold</b> (KNG-74) - for {@link Settings#actionBarHoldMillis()} after a shown
 *       message, {@link #holdsActionBar} tells other action-bar users to keep off it.</li>
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

    /**
     * One refusal's outcome: whether to show the message (action bar), whether to also send it to
     * chat (first shown message of an episode; implies {@code showMessage}), and the load-guard action.
     */
    public record Outcome(boolean showMessage, boolean showInChat, Action action) {
    }

    /**
     * @param messageIntervalMillis    minimum time between two deny messages to one player
     * @param loadGuardEnabled         whether the load guard acts at all
     * @param maxRefusalsPerSecond     sustained refusal rate above which the load guard acts
     * @param windowMillis             how long the rate must be sustained
     * @param escalationWindowMillis   a second breach within this time after the teleport kicks
     * @param chatQuietPeriodMillis    a refusal after this long without one starts a new episode and
     *                                 is also said in chat (0 = every shown message goes to chat too)
     * @param actionBarHoldMillis      how long after a shown message other HUDs keep off the action bar
     */
    public record Settings(long messageIntervalMillis, boolean loadGuardEnabled, int maxRefusalsPerSecond,
                           long windowMillis, long escalationWindowMillis, long chatQuietPeriodMillis,
                           long actionBarHoldMillis) {
        public static final long DEFAULT_CHAT_QUIET_PERIOD_MILLIS = 10_000;
        public static final long DEFAULT_ACTION_BAR_HOLD_MILLIS = 3_000;

        /** The KNG-56 settings with the KNG-74 chat quiet period and action-bar hold at their defaults. */
        public Settings(long messageIntervalMillis, boolean loadGuardEnabled, int maxRefusalsPerSecond,
                        long windowMillis, long escalationWindowMillis) {
            this(messageIntervalMillis, loadGuardEnabled, maxRefusalsPerSecond, windowMillis, escalationWindowMillis,
                DEFAULT_CHAT_QUIET_PERIOD_MILLIS, DEFAULT_ACTION_BAR_HOLD_MILLIS);
        }

        public static Settings defaults() {
            return new Settings(2000, true, 20, 3000, 60000);
        }

        int maxInWindow() {
            return (int) Math.max(1, Math.ceil(maxRefusalsPerSecond * (windowMillis / 1000.0)));
        }
    }

    private static final class PlayerState {
        long lastMessage = Long.MIN_VALUE;
        long lastRefusal = Long.MIN_VALUE;
        String lastChatReason;
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

    /** Record one refused move by {@code playerId} at {@code nowMillis}, reason unknown. */
    public Outcome onRefusal(UUID playerId, long nowMillis) {
        return onRefusal(playerId, nowMillis, null);
    }

    /**
     * Record one refused move by {@code playerId} at {@code nowMillis}.
     *
     * @param reason what the player is told (e.g. the message text); a shown message with another
     *               reason than the one last said in chat goes to chat again. {@code null} = unknown.
     */
    public Outcome onRefusal(UUID playerId, long nowMillis, String reason) {
        PlayerState state = players.computeIfAbsent(playerId, id -> new PlayerState());
        synchronized (state) {
            boolean notify = state.lastMessage == Long.MIN_VALUE
                || nowMillis - state.lastMessage >= settings.messageIntervalMillis();
            boolean quietBefore = state.lastRefusal == Long.MIN_VALUE
                || nowMillis - state.lastRefusal >= settings.chatQuietPeriodMillis();
            boolean newReason = reason != null && !Objects.equals(reason, state.lastChatReason);
            state.lastRefusal = nowMillis;
            boolean chat = notify && (quietBefore || newReason);
            if (notify) {
                state.lastMessage = nowMillis;
            }
            if (chat) {
                state.lastChatReason = reason;
            }
            if (!settings.loadGuardEnabled()) {
                return new Outcome(notify, chat, Action.NONE);
            }

            state.refusals.addLast(nowMillis);
            while (!state.refusals.isEmpty() && nowMillis - state.refusals.peekFirst() >= settings.windowMillis()) {
                state.refusals.removeFirst();
            }
            if (state.refusals.size() <= settings.maxInWindow()) {
                return new Outcome(notify, chat, Action.NONE);
            }

            state.refusals.clear();
            boolean recentlyTeleported = state.lastTeleport != Long.MIN_VALUE
                && nowMillis - state.lastTeleport < settings.escalationWindowMillis();
            if (recentlyTeleported) {
                state.lastTeleport = Long.MIN_VALUE;
                state.lastMessage = nowMillis;
                return new Outcome(true, chat, Action.KICK);
            }
            state.lastTeleport = nowMillis;
            state.lastMessage = nowMillis;
            return new Outcome(true, chat, Action.TELEPORT_TO_SPAWN);
        }
    }

    /**
     * Whether a deny message shown to {@code playerId} is still fresh at {@code nowMillis} (within
     * {@link Settings#actionBarHoldMillis()}), so other action-bar users (the navigation arrow)
     * should not overwrite it yet.
     */
    public boolean holdsActionBar(UUID playerId, long nowMillis) {
        PlayerState state = players.get(playerId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.lastMessage != Long.MIN_VALUE && nowMillis - state.lastMessage < settings.actionBarHoldMillis();
        }
    }

    /** Forget a player (quit). */
    public void forget(UUID playerId) {
        players.remove(playerId);
    }
}
