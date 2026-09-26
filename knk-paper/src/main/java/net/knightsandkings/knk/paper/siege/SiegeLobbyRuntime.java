package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeLobby;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.siege.SiegeDisplayText;
import net.knightsandkings.knk.core.siege.SiegeLobbyStateMachine;
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
 * the current round - the draw's match token and Phase 6 match id, the team split, and the running
 * {@link SiegeMatch}. Read-only to everything outside {@link SiegeService}; commands and the Phase 8b
 * menu views read it through these getters. Main thread only.
 */
public final class SiegeLobbyRuntime {

    private final SiegeLobbyStateMachine machine;
    private final Set<UUID> members = new LinkedHashSet<>();
    private final Map<UUID, Integer> userIds = new HashMap<>();

    private String matchToken;
    private CompletableFuture<Long> matchId;
    private Map<Integer, List<UUID>> split;
    private SiegeMatch match;
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

    public Optional<KnkSiegeScenario> drawnScenario() {
        return machine.drawnScenario();
    }

    public Optional<Map<Integer, List<UUID>>> split() {
        return Optional.ofNullable(split);
    }

    public Optional<String> matchToken() {
        return Optional.ofNullable(matchToken);
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

    void setMatch(SiegeMatch match) {
        this.match = match;
    }

    /** Clears everything round-specific (after an end or a cancel). Members are handled by the caller. */
    void clearRound() {
        matchToken = null;
        matchId = null;
        split = null;
        match = null;
    }

    boolean disabledLogged() {
        return disabledLogged;
    }

    void setDisabledLogged(boolean disabledLogged) {
        this.disabledLogged = disabledLogged;
    }
}
