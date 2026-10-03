package net.knightsandkings.knk.paper.siege;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.siege.ObjectiveState.CaptureEvent;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.MemberView;
import net.knightsandkings.knk.core.siege.SiegePhase;
import net.knightsandkings.knk.core.telemetry.TelemetryEvent;
import net.knightsandkings.knk.core.telemetry.TelemetryEventNames;
import net.knightsandkings.knk.paper.telemetry.TelemetryEmitter;

/**
 * Siege lobby and match diagnostics (KNG-34 link 6, DESIGN.md §F.12 "Siege event catalogue", "First
 * vertical slice"): join attempts with stable reason codes, votes, team assignment, match join/leave,
 * phase changes and captures. Lives in the siege package to read the lobby's recorded match id (the
 * API's {@code SiegeMatch.Id}, so the owner timeline links to the match). Observes only; never changes
 * the Siege flow. Registered only when {@code telemetry.enabled} is true.
 */
public final class SiegeTelemetryObserver implements SiegeMatchObserver {

    private final TelemetryEmitter emitter;
    private final Map<Integer, SiegePhase> lastPhase = new HashMap<>();

    public SiegeTelemetryObserver(TelemetryEmitter emitter) {
        this.emitter = emitter;
    }

    /** The API's id of the lobby's current match, when the create call has answered. */
    static Integer matchIdOf(SiegeLobbyRuntime lobby) {
        if (lobby == null) {
            return null;
        }
        CompletableFuture<Long> future = lobby.matchIdFuture();
        if (future == null || !future.isDone() || future.isCompletedExceptionally()) {
            return null;
        }
        Long id = future.getNow(null);
        return id == null || id > Integer.MAX_VALUE ? null : id.intValue();
    }

    @Override
    public void joinAttempted(SiegeLobbyRuntime lobby, Player player, boolean joined, String reasonCode) {
        TelemetryEmitter.Event event = emitter.event(TelemetryEventNames.SIEGE_LOBBY_JOIN_ATTEMPT)
            .player(player.getUniqueId())
            .outcome(joined ? TelemetryEvent.Outcome.SUCCEEDED : TelemetryEvent.Outcome.DENIED)
            .reason(reasonCode);
        if (lobby != null) {
            event.object("siege_lobby", lobby.id())
                .put("lobbyId", lobby.id())
                .put("phase", lobby.phase().name().toLowerCase(Locale.ROOT))
                .put("members", lobby.memberCount());
        }
        event.emit();
    }

    @Override
    public void voteCast(SiegeLobbyRuntime lobby, Player player, Integer scenarioId, String result) {
        boolean accepted = "CAST".equals(result) || "CHANGED".equals(result) || "REMOVED".equals(result);
        emitter.event(TelemetryEventNames.SIEGE_VOTE_CAST)
            .player(player.getUniqueId())
            .outcome(accepted ? TelemetryEvent.Outcome.SUCCEEDED : TelemetryEvent.Outcome.DENIED)
            .reason(result == null ? null : result.toLowerCase(Locale.ROOT))
            .object("siege_lobby", lobby.id())
            .put("lobbyId", lobby.id())
            .put("scenarioId", scenarioId)
            .emit();
    }

    @Override
    public void teamAssigned(SiegeLobbyRuntime lobby, UUID playerId, int teamId, String cause) {
        emitter.event(TelemetryEventNames.SIEGE_TEAM_ASSIGNMENT)
            .player(playerId)
            .user(lobby.userIds().get(playerId))
            .outcome(TelemetryEvent.Outcome.INFO)
            .reason(cause)
            .object("siege_lobby", lobby.id())
            .put("lobbyId", lobby.id())
            .put("teamId", teamId)
            .put("cause", cause)
            .emit();
    }

    @Override
    public void matchStarted(SiegeLobbyRuntime lobby, SiegeMatch match) {
        Integer matchId = matchIdOf(lobby);
        for (MemberView member : match.roster().members()) {
            emitter.event(TelemetryEventNames.SIEGE_MATCH_JOIN)
                .player(member.playerId())
                .user(lobby.userIds().get(member.playerId()))
                .match(matchId)
                .outcome(TelemetryEvent.Outcome.SUCCEEDED)
                .object("siege_lobby", lobby.id())
                .put("lobbyId", lobby.id())
                .put("teamId", member.teamId())
                .put("allianceGroup", match.alliances().allianceOf(member.teamId()))
                .emit();
        }
    }

    @Override
    public void memberLeft(SiegeLobbyRuntime lobby, UUID playerId, Integer userId, String cause, boolean duringMatch) {
        if (!duringMatch) {
            return;
        }
        emitter.event(TelemetryEventNames.SIEGE_MATCH_LEAVE)
            .player(playerId)
            .user(userId)
            .match(matchIdOf(lobby))
            .outcome(TelemetryEvent.Outcome.INFO)
            .reason(cause)
            .object("siege_lobby", lobby.id())
            .put("lobbyId", lobby.id())
            .put("cause", cause)
            .emit();
    }

    @Override
    public void objectiveCaptured(SiegeLobbyRuntime lobby, SiegeMatch match, CaptureEvent capture) {
        emitter.event(TelemetryEventNames.SIEGE_OBJECTIVE_CAPTURED)
            .player(capture.capturerId())
            .user(capture.capturerId() == null ? null : lobby.userIds().get(capture.capturerId()))
            .match(matchIdOf(lobby))
            .outcome(TelemetryEvent.Outcome.SUCCEEDED)
            .object("siege_objective", capture.objectiveId())
            .put("lobbyId", lobby.id())
            .put("objectiveId", capture.objectiveId())
            .put("teamId", capture.newHolderTeamId())
            .emit();
    }

    /** Phase changes are seen through lobbyChanged (any change); one event per real transition. */
    @Override
    public void lobbyChanged(SiegeLobbyRuntime lobby) {
        SiegePhase now = lobby.phase();
        SiegePhase before = lastPhase.put(lobby.id(), now);
        if (before == null || before == now) {
            return;
        }
        emitter.event(TelemetryEventNames.SIEGE_MATCH_PHASE)
            .match(matchIdOf(lobby))
            .outcome(TelemetryEvent.Outcome.INFO)
            .reason(now.name().toLowerCase(Locale.ROOT))
            .object("siege_lobby", lobby.id())
            .put("lobbyId", lobby.id())
            .put("from", before.name().toLowerCase(Locale.ROOT))
            .put("to", now.name().toLowerCase(Locale.ROOT))
            .put("members", lobby.memberCount())
            .emit();
    }
}
