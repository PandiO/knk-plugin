package net.knightsandkings.knk.core.siege;

/**
 * What a siege-locked gate means to the player navigating past it (KNG-234, developer decision 2026-10-22: a route
 * keeps the gates' current state in mind - an attacker bound for an objective behind a closed gate that has to be
 * captured is <b>not</b> routed as if it were open).
 *
 * <p>The gate's own state still comes first ({@code GateAvailability}: an open or destroyed gate is open, whoever
 * walks up to it). This only says how a gate that is <i>closed</i> and locked by a siege reads:
 * <ul>
 *   <li>to a <b>member whose alliance owns it</b>: a gate they can open themselves (right-click) - the route passes
 *       it with that hint;</li>
 *   <li>to any other <b>member</b>: closed and locked - the route stops there ("locked for a siege") until the
 *       gate opens (captured, destroyed, opened by its owners) and the route is re-planned;</li>
 *   <li>to a <b>non-member</b>: as before - carried through when the siege's non-member rule says so.</li>
 * </ul>
 *
 * @param siegeLocked  the gate counts as locked for this player
 * @param siegeCarries the siege carries this player through (non-members only)
 * @param passable     the player can pass it by right-clicking (the gate's owners)
 */
public record SiegeGateStance(boolean siegeLocked, boolean siegeCarries, boolean passable) {

    public static SiegeGateStance of(boolean locked, boolean member, boolean memberMayControl, boolean nonMemberCarried) {
        if (!locked) {
            return new SiegeGateStance(false, false, false);
        }
        if (member) {
            return memberMayControl
                    ? new SiegeGateStance(false, false, true)
                    : new SiegeGateStance(true, false, false);
        }
        return new SiegeGateStance(true, nonMemberCarried, false);
    }
}
