package net.knightsandkings.knk.core.domain.siege;

import java.util.List;

/**
 * Siege Phase 7a request/response shapes for the persisted gate lockdown
 * ({@code /api/siege-matches/{id}/gate-lockdown}, {@code …/gate-restore}, {@code …/restore-stale-gates},
 * DESIGN §8.2, §8.4). Door states are resting states: {@code opened} is true for open (or opening).
 */
public final class KnkSiegeGateRecords {
    private KnkSiegeGateRecords() {}

    /** One door's runtime state right before the lockdown (or as a snapshot holds it). */
    public record DoorState(int gateDoorId, boolean opened, double healthCurrent, boolean destroyed) {}

    /**
     * One affected gate structure and its role for the match (DESIGN §8.1).
     *
     * @param invincible selected but not damageable, or an area gate
     * @param forcedOpen an area gate (not selected): kept open for the match
     */
    public record LockdownEntry(int gateStructureId, boolean objectiveGate, boolean invincible, boolean forcedOpen,
                                List<DoorState> doors) {
        public LockdownEntry {
            doors = doors == null ? List.of() : List.copyOf(doors);
        }
    }

    /** A snapshot the server re-applied (restore) - the doors' pre-lockdown states. */
    public record RestoredGate(long siegeMatchId, int gateStructureId, List<DoorState> doors) {
        public RestoredGate {
            doors = doors == null ? List.of() : List.copyOf(doors);
        }
    }

    public record RestoreResult(List<RestoredGate> restored, List<Integer> clearedGateStructureIds) {
        public RestoreResult {
            restored = restored == null ? List.of() : List.copyOf(restored);
            clearedGateStructureIds = clearedGateStructureIds == null ? List.of() : List.copyOf(clearedGateStructureIds);
        }

        public boolean isEmpty() {
            return restored.isEmpty() && clearedGateStructureIds.isEmpty();
        }
    }
}
