package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeEndReason;

import java.util.List;

/**
 * What {@link SiegeLobbyStateMachine} asks the Paper runtime to do. The state machine is pure; the
 * runtime (Phase 5 {@code SiegeService}) applies effects in list order on the main thread, e.g.
 * with an exhaustive {@code switch (effect) { case SiegeEffect.SendToHubEffect e -> ... }}.
 * Message texts are the runtime's; effects carry only the facts.
 */
public sealed interface SiegeEffect {

    enum Audience {
        /** Every online player (join advert). */
        ONLINE,
        /** The lobby's members. */
        MEMBERS
    }

    enum Announcement {
        /** Matchmaking opened; {@code seconds} = seconds until the match starts. */
        MATCHMAKING_OPENED,
        /** A configured countdown mark; {@code seconds} = seconds until the match starts. */
        MATCHMAKING_COUNTDOWN,
        /** Voting closed; {@code seconds} = seconds until the hub teleport (fixes N8's wording source). */
        VOTING_CLOSED
    }

    /** Why a matchmaking round was called off before a match started. */
    enum CancelReason {
        /** Fewer members than the drawn scenario's PlayersMin at the draw or at the start. */
        NOT_ENOUGH_PLAYERS,
        /** No rotation scenario was available (all locked by other lobbies). */
        NO_SCENARIO_AVAILABLE,
        ADMIN_STOPPED,
        SERVER_RESTART
    }

    enum DisableReason {
        /** The lobby's rotation has no ready scenario (DESIGN §6.1: stay disabled, log once). */
        NO_READY_SCENARIO,
        /** Stopped by an admin or on shutdown. */
        STOPPED
    }

    /** The lobby changed phase (refresh menus, scoreboards). */
    record PhaseChangedEffect(SiegePhase from, SiegePhase to) implements SiegeEffect {}

    /** Send an announcement; see {@link Announcement} for what {@code seconds} means. */
    record AnnounceEffect(Audience audience, Announcement announcement, int seconds, boolean joinable) implements SiegeEffect {}

    /** Matchmaking started and voting is open for these candidates (1–3, in draw order). */
    record MatchmakingStartedEffect(List<KnkSiegeScenario> candidates) implements SiegeEffect {
        public MatchmakingStartedEffect {
            candidates = List.copyOf(candidates);
        }
    }

    /**
     * The scenario was drawn and locked (T-draw). Runtime: remove members the scenario excludes
     * (title bracket, over capacity; DESIGN §6.2), announce it, create the {@code SiegeMatch} (Phase 6).
     */
    record DrawScenarioEffect(KnkSiegeScenario scenario, VoteTally.Draw draw) implements SiegeEffect {}

    /** T-hub: snapshot and teleport every member to the hub (DESIGN §6.4, §9.1); joining is closed. */
    record SendToHubEffect(KnkSiegeScenario scenario) implements SiegeEffect {}

    /** T-split: split the members into the scenario's teams ({@link TeamPartitioner}). */
    record SplitTeamsEffect(KnkSiegeScenario scenario) implements SiegeEffect {}

    /** T-0: start the match (DESIGN §6.5); it lasts {@code durationSeconds}. */
    record StartMatchEffect(KnkSiegeScenario scenario, int durationSeconds, int memberCount) implements SiegeEffect {}

    /** Two seconds into the match: each team's {@code startMessage} on the action bar (DESIGN §6.5). */
    record StartMessageEffect(KnkSiegeScenario scenario) implements SiegeEffect {}

    /**
     * The match is over. Runtime: resolve the winner ({@link WinResolver}, which returns "aborted" for
     * admin stop / restart), announce, restore members and gates, complete or abort the
     * {@code SiegeMatch} (Phase 6).
     */
    record EndMatchEffect(KnkSiegeScenario scenario, SiegeEndReason reason) implements SiegeEffect {}

    /**
     * The round was called off before a match started. Runtime: announce, release every member
     * (restoring them first when {@code membersInHub}); no match is recorded.
     */
    record CancelMatchmakingEffect(CancelReason reason, boolean membersInHub) implements SiegeEffect {}

    /**
     * Between matches: fetch a fresh runtime config ({@code SiegeDataAccess.refreshRuntimeConfigAsync})
     * and hand this lobby's entry to {@link SiegeLobbyStateMachine#offerConfiguration}.
     */
    record RefreshConfigEffect(int lobbyId) implements SiegeEffect {}

    /** The lobby is disabled; log once (DESIGN §6.1). */
    record LobbyDisabledEffect(DisableReason reason) implements SiegeEffect {}
}
