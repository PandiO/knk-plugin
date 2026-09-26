package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Cross-lobby runtime locks (DESIGN §5.5), shared by every lobby on the server:
 * <ul>
 *   <li>a scenario runs in at most one lobby at a time;</li>
 *   <li>at most one scenario runs per town at a time (so gates, districts and the area lockdown
 *       never overlap);</li>
 *   <li>a selected gate belongs to at most one running scenario (implied by the town rule; checked
 *       anyway);</li>
 *   <li>a player is a member of at most one lobby.</li>
 * </ul>
 * {@code SiegeLobbyStateMachine} takes the scenario lock at the draw and releases it when the lobby
 * enters cooldown or is disabled, so it covers HUB, IN_PROGRESS and ENDING (gate restore). The
 * Paper runtime claims players on join and releases them on leave.
 * Synchronized for safety, although the siege runtime uses it from the main thread only.
 */
public final class SiegeRuntimeLocks {

    private record Hold(int scenarioId, int townId, Set<Integer> gateIds) {}

    private final Map<Integer, Hold> holdsByLobby = new LinkedHashMap<>();
    private final Map<UUID, Integer> lobbyByPlayer = new HashMap<>();

    /** Could {@code lobbyId} lock this scenario now? (Its own current lock counts as available.) */
    public synchronized boolean isScenarioAvailable(int lobbyId, KnkSiegeScenario scenario) {
        return conflictFor(lobbyId, scenario).isEmpty();
    }

    /**
     * Locks the scenario, its town and its gates for {@code lobbyId}.
     *
     * @return false when another lobby holds the scenario, the town or one of the gates
     * @throws IllegalStateException when the lobby already holds a different scenario
     */
    public synchronized boolean tryLock(int lobbyId, KnkSiegeScenario scenario) {
        Hold current = holdsByLobby.get(lobbyId);
        if (current != null) {
            if (current.scenarioId() == scenario.id()) return true;
            throw new IllegalStateException("Lobby " + lobbyId + " already holds scenario " + current.scenarioId());
        }
        if (conflictFor(lobbyId, scenario).isPresent()) return false;
        holdsByLobby.put(lobbyId, new Hold(scenario.id(), scenario.townId(), Set.copyOf(scenario.gateStructureIds())));
        return true;
    }

    /** Releases the lobby's scenario lock, if any. */
    public synchronized void release(int lobbyId) {
        holdsByLobby.remove(lobbyId);
    }

    public synchronized OptionalInt lockedScenarioOf(int lobbyId) {
        Hold hold = holdsByLobby.get(lobbyId);
        return hold == null ? OptionalInt.empty() : OptionalInt.of(hold.scenarioId());
    }

    public synchronized OptionalInt lobbyHoldingScenario(int scenarioId) {
        return find(h -> h.scenarioId() == scenarioId);
    }

    public synchronized OptionalInt lobbyHoldingTown(int townId) {
        return find(h -> h.townId() == townId);
    }

    /** The lobby whose running scenario selected this gate (Phase 7 gate listeners). */
    public synchronized OptionalInt lobbyHoldingGate(int gateStructureId) {
        return find(h -> h.gateIds().contains(gateStructureId));
    }

    /** @return false when the player is already a member of another lobby */
    public synchronized boolean tryClaimPlayer(UUID playerId, int lobbyId) {
        Integer current = lobbyByPlayer.get(playerId);
        if (current != null && current != lobbyId) return false;
        lobbyByPlayer.put(playerId, lobbyId);
        return true;
    }

    public synchronized void releasePlayer(UUID playerId) {
        lobbyByPlayer.remove(playerId);
    }

    /** Releases every player claimed by a lobby (lobby stopped or matchmaking cancelled). */
    public synchronized void releasePlayers(int lobbyId) {
        lobbyByPlayer.values().removeIf(id -> id == lobbyId);
    }

    public synchronized Optional<Integer> lobbyOfPlayer(UUID playerId) {
        return Optional.ofNullable(lobbyByPlayer.get(playerId));
    }

    private Optional<Integer> conflictFor(int lobbyId, KnkSiegeScenario scenario) {
        for (Map.Entry<Integer, Hold> entry : holdsByLobby.entrySet()) {
            if (entry.getKey() == lobbyId) continue;
            Hold hold = entry.getValue();
            boolean gateOverlap = scenario.gateStructureIds().stream().anyMatch(hold.gateIds()::contains);
            if (hold.scenarioId() == scenario.id() || hold.townId() == scenario.townId() || gateOverlap) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    private OptionalInt find(Predicate<Hold> predicate) {
        return holdsByLobby.entrySet().stream()
                .filter(e -> predicate.test(e.getValue()))
                .mapToInt(Map.Entry::getKey)
                .findFirst();
    }
}
