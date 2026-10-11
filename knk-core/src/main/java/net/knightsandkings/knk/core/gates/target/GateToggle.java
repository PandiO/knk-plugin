package net.knightsandkings.knk.core.gates.target;

import net.knightsandkings.knk.core.domain.gates.AnimationState;

import java.util.Collection;

/**
 * What {@code toggle} does (KNG-77), at both command layers.
 * <ul>
 *   <li>A door flips its <em>target</em> state: open or opening closes, closed or closing opens -
 *       the same as typing the matching {@code close}/{@code open}.</li>
 *   <li>A gate structure with mixed door states: if any of its (active) doors is open or opening,
 *       all of them close; otherwise all of them open. One press therefore always ends with the
 *       whole gate shut when any part of it was open.</li>
 * </ul>
 */
public final class GateToggle {
    private GateToggle() {
    }

    /** True when the door is open or on its way there. A null (unknown) state counts as closed. */
    public static boolean isOpenOrOpening(AnimationState state) {
        return state == AnimationState.OPEN || state == AnimationState.OPENING;
    }

    /** True when toggling a door in {@code state} opens it, false when it closes it. */
    public static boolean doorOpens(AnimationState state) {
        return !isOpenOrOpening(state);
    }

    /** True when toggling a structure whose doors are in these states opens them all. */
    public static boolean structureOpens(Collection<AnimationState> doorStates) {
        return doorStates.stream().noneMatch(GateToggle::isOpenOrOpening);
    }
}
