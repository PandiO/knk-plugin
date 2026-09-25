package net.knightsandkings.knk.core.domain.siege;

import net.knightsandkings.knk.core.domain.location.KnkLocation;

/**
 * A scenario objective as runtime-config resolves it (DESIGN §3.6).
 *
 * @param captureLocation     the objective's own location, else its gate structure's (resolved server-side)
 * @param gateStructureId     the objective gate, or null for a location-only objective
 * @param capturePoints       points at the start; reaching 0 captures it
 * @param instantVictory      capturing it ends the match ("main" objective)
 * @param initialHolderTeamId explicit holder, else the scenario's first Defender team (resolved server-side)
 * @param spawnWhenHeld       the holding team may spawn here (vision §7.2)
 * @param gateStateOnCapture  state the objective gate is forced to on capture (D3, default OPEN)
 */
public record KnkSiegeObjective(
        int id,
        int sortOrder,
        String name,
        KnkLocation captureLocation,
        Integer gateStructureId,
        int capturePoints,
        double captureRadius,
        boolean instantVictory,
        int initialHolderTeamId,
        boolean spawnWhenHeld,
        SiegeGateState gateStateOnCapture
) {
    public KnkSiegeObjective {
        if (gateStateOnCapture == null) gateStateOnCapture = SiegeGateState.OPEN;
    }

    public boolean hasGate() {
        return gateStructureId != null;
    }
}
