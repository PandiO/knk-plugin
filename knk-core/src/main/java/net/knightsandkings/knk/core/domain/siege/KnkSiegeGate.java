package net.knightsandkings.knk.core.domain.siege;

/**
 * An admin-selected gate (DESIGN §3.7, D3). Other gates in the scenario's area aren't listed;
 * Phase 7 forces them open.
 *
 * @param initialOwnerTeamId explicit owner, else the scenario's first Defender team (resolved server-side)
 * @param initialState       state forced at match start
 * @param objectiveGate      some objective references this gate (resolved server-side)
 */
public record KnkSiegeGate(
        int gateStructureId,
        String name,
        int initialOwnerTeamId,
        SiegeGateState initialState,
        boolean damageable,
        boolean objectiveGate
) {
    public KnkSiegeGate {
        if (initialState == null) initialState = SiegeGateState.CLOSED;
    }
}
