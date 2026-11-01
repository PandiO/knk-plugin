package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeLobby;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.siege.SiegeDisplayText;
import net.knightsandkings.knk.core.siege.SiegeLobbyStateMachine;
import net.knightsandkings.knk.core.siege.SiegeMatchRoster.SpawnChoice;
import net.knightsandkings.knk.core.siege.SiegeObjectiveBoard;
import net.knightsandkings.knk.core.siege.SiegePhase;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One lobby at runtime: its {@link SiegeLobbyStateMachine}, its members (join order) and the state of
 * the current round - the draw's match token and Phase 6 match id, the team split, the members' spawn
 * choices made at the hub before the start (playtest 2026-10-24: the spawn picker opens at the team split,
 * against the scenario's initial objective holdings), and the running {@link SiegeMatch}. Read-only to
 * everything outside {@link SiegeService}; commands and the Phase 8b menu views read it through these
 * getters. Main thread only.
 */
public final class SiegeLobbyRuntime {

    private final SiegeLobbyStateMachine machine;
    private final Set<UUID> members = new LinkedHashSet<>();
    private final Map<UUID, Integer> userIds = new HashMap<>();

    private String matchToken;
    private CompletableFuture<Long> matchId;
    private Map<Integer, List<UUID>> split;
    /** The scenario as it starts (initial holders), for spawn options between the split and the start. */
    private SiegeObjectiveBoard preMatchBoard;
    private final Map<UUID, SpawnChoice> preMatchSpawnChoices = new HashMap<>();
    private SiegeMatch match;
    /** The match ended and was recorded; its members wait at the hub with the result (PandiO/knk-workspace#198). */
    private boolean matchOver;
    private boolean disabledLogged;

    SiegeLobbyRuntime(SiegeLobbyStateMachine machine) {
        this.machine = machine;
    }

    public SiegeLobbyStateMachine machine() {
        return machine;
    }

    public int id() {
        return machine.lobby().id();
    }

    public KnkSiegeLobby lobby() {
        return machine.lobby();
    }

    public String key() {
        return machine.lobby().key();
    }

    /** The lobby name for display (repaired and trimmed; the stored value is untouched). */
    public String displayName() {
        return SiegeDisplayText.clean(machine.lobby().name(), machine.lobby().key());
    }

    public SiegePhase phase() {
        return machine.phase();
    }

    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    public int memberCount() {
        return members.size();
    }

    public boolean isMember(UUID playerId) {
        return members.contains(playerId);
    }

    public Optional<SiegeMatch> match() {
        return Optional.ofNullable(match);
    }

    /**
     * True from the end of a match until its members are released (alpha run 1, PandiO/knk-workspace#198): the
     * match is recorded and its rewards granted, and the members wait at the hub with the result (POST_MATCH).
     */
    public boolean matchOver() {
        return matchOver;
    }

    public Optional<KnkSiegeScenario> drawnScenario() {
        return machine.drawnScenario();
    }

    public Optional<Map<Integer, List<UUID>>> split() {
        return Optional.ofNullable(split);
    }

    public Optional<String> matchToken() {
        return Optional.ofNullable(matchToken);
    }

    /** The member's team from the split (HUB, after the team split), else empty. */
    public Optional<KnkSiegeTeam> splitTeamOf(UUID playerId) {
        if (split == null) return Optional.empty();
        Optional<KnkSiegeScenario> scenario = drawnScenario();
        if (scenario.isEmpty()) return Optional.empty();
        return split.entrySet().stream()
                .filter(e -> e.getValue().contains(playerId))
                .findFirst()
                .flatMap(e -> scenario.get().team(e.getKey()));
    }

    /** The scenario's objectives as the match starts; present from the team split until the start. */
    public Optional<SiegeObjectiveBoard> preMatchBoard() {
        return Optional.ofNullable(preMatchBoard);
    }

    /** A member's spawn choice made before the start (carried into the match roster at T-0). */
    public Optional<SpawnChoice> preMatchSpawnChoice(UUID playerId) {
        return Optional.ofNullable(preMatchSpawnChoices.get(playerId));
    }

    // ==================== Mutators (SiegeService only) ====================

    Set<UUID> mutableMembers() {
        return members;
    }

    Map<UUID, Integer> userIds() {
        return userIds;
    }

    void startRound(String token, CompletableFuture<Long> matchIdFuture) {
        this.matchToken = token;
        this.matchId = matchIdFuture;
    }

    CompletableFuture<Long> matchIdFuture() {
        return matchId;
    }

    void setSplit(Map<Integer, List<UUID>> split) {
        this.split = split;
    }

    void setPreMatchBoard(SiegeObjectiveBoard board) {
        this.preMatchBoard = board;
    }

    Map<UUID, SpawnChoice> preMatchSpawnChoices() {
        return preMatchSpawnChoices;
    }

    void setMatch(SiegeMatch match) {
        this.match = match;
    }

    void setMatchOver(boolean matchOver) {
        this.matchOver = matchOver;
    }

    /** Clears everything round-specific (after an end or a cancel). Members are handled by the caller. */
    void clearRound() {
        matchToken = null;
        matchId = null;
        split = null;
        preMatchBoard = null;
        preMatchSpawnChoices.clear();
        match = null;
        matchOver = false;
    }

    boolean disabledLogged() {
        return disabledLogged;
    }

    void setDisabledLogged(boolean disabledLogged) {
        this.disabledLogged = disabledLogged;
    }
}
