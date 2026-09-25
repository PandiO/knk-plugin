package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.siege.ObjectiveState.CaptureEvent;
import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard.BoardStep;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/**
 * Hooks {@link SiegeService} calls on the main thread as a lobby moves through its loop, so the
 * presenters (5b), enchant books (5c) and, in Phase 8b, the menu bridge ({@code refreshOpenMenus}
 * for {@code siege.*}) stay out of the service. Every method has a no-op default; an observer that
 * throws is logged and skipped, never allowed to break the ticker.
 */
public interface SiegeMatchObserver {

    /** Any phase change, member join/leave or vote (menus, scoreboards). */
    default void lobbyChanged(SiegeLobbyRuntime lobby) { }

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

    /** Plugin disable, after every lobby was stopped. */
    default void shutdown() { }
}
