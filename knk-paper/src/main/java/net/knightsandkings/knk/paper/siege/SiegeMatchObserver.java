package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.siege.ObjectiveState.CaptureEvent;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard.BoardStep;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hooks {@link SiegeService} calls on the main thread as a lobby moves through its loop, so the
 * presenters (5b), enchant books (5c) and, in Phase 8b, the menu bridge ({@code refreshOpenMenus}
 * for {@code siege.*}) stay out of the service. Every method has a no-op default; an observer that
 * throws is logged and skipped, never allowed to break the ticker.
 */
public interface SiegeMatchObserver {

    /** Any phase change, member join/leave or vote (menus, scoreboards). */
    default void lobbyChanged(SiegeLobbyRuntime lobby) { }

    /**
     * Phase 7a: the round reached the hub (T-15, DESIGN §6.4) - the scenario area locks down
     * (gates, §8.2; entry, §8.5) before anyone can fight. Members are already at the hub.
     */
    default void areaLockdownStarted(SiegeLobbyRuntime lobby, KnkSiegeScenario scenario) { }

    /** The match started; members are at their spawnpoints. */
    default void matchStarted(SiegeLobbyRuntime lobby, SiegeMatch match) { }

    /** One second of a running match, after the capture step. */
    default void secondTicked(SiegeLobbyRuntime lobby, SiegeMatch match, BoardStep step,
                              Map<Integer, List<Presence>> presence) { }

    /** An objective changed hands (after the roster and announcements). Phase 7: objective gate transfer. */
    default void objectiveCaptured(SiegeLobbyRuntime lobby, SiegeMatch match, CaptureEvent capture) { }

    /** A member left the running match (leave, quit, kick) before the vault restore. */
    default void memberRemoved(SiegeLobbyRuntime lobby, SiegeMatch match, Player player) { }

    /** The match ended (any reason), before members are restored. */
    default void matchEnded(SiegeLobbyRuntime lobby, SiegeMatch match) { }

    /**
     * Phase 7a: the round is over (match ended, matchmaking cancelled, admin stop, shutdown), after
     * every member was released and before the round state is cleared - the lockdown's restore point.
     */
    default void roundReleased(SiegeLobbyRuntime lobby) { }

    /** Plugin disable, after every lobby was stopped. */
    default void shutdown() { }

    // KNG-34 link 6 (diagnostic telemetry): lobby participation, reported with stable codes only.

    /**
     * A {@code /siege join} (or menu join) was answered.
     *
     * @param lobby      the lobby asked for; null when none could be chosen
     * @param joined     the player is now a member
     * @param reasonCode {@code joined}, or why not ({@code no_permission}, {@code lobby_full}, ...)
     */
    default void joinAttempted(SiegeLobbyRuntime lobby, Player player, boolean joined, String reasonCode) { }

    /**
     * A scenario vote was handled by the lobby's state machine.
     *
     * @param scenarioId the voted scenario; null for Random
     * @param result     the {@code VoteResult} name (CAST, CHANGED, REMOVED, CLOSED, ...)
     */
    default void voteCast(SiegeLobbyRuntime lobby, Player player, Integer scenarioId, String result) { }

    /** Teams were split at the hub: one call per member. */
    default void teamAssigned(SiegeLobbyRuntime lobby, UUID playerId, int teamId, String cause) { }

    /**
     * A member left the lobby or match (any phase).
     *
     * @param userId the player's user id (null when it was never known)
     * @param cause  lower-case leave cause: leave, quit, kick, excluded_at_draw, snapshot_failed, round_over, shutdown
     */
    default void memberLeft(SiegeLobbyRuntime lobby, UUID playerId, Integer userId, String cause, boolean duringMatch) { }
}
