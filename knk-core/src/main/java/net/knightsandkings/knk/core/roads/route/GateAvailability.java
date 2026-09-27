package net.knightsandkings.knk.core.roads.route;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.roads.RoadEdge;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Gate doors on an edge (DESIGN §6.7 first row, plan D2/D13, reuse R5/R39/R25), through two ports
 * the paper side fills from {@code GateManager} / {@code CachedGateDoor}'s <i>effective</i>
 * accessors, {@code SiegeGateController} and {@code GatePassThroughRules}. Per door, in order:
 * <ol>
 *   <li>destroyed → OPEN (nothing stands there);</li>
 *   <li>state OPEN → OPEN;</li>
 *   <li>OPENING / CLOSING, or jammed → BLOCKED (mid-animation, no way to know when it ends);</li>
 *   <li>siege-locked → PASS_THROUGH when the siege's non-member rule carries the player through
 *       (R39, D2), else BLOCKED;</li>
 *   <li>closed with pass-through allowed for this player → PASS_THROUGH with the hint;</li>
 *   <li>otherwise BLOCKED ("the West Gate is closed").</li>
 * </ol>
 * A door the port does not know is treated as OPEN (Phase 2d decision: the edge's tag is stale,
 * nothing can be checked). Decisions are cached per door id for the request. An edge with several
 * doors gets the strictest verdict, first door first.
 */
public final class GateAvailability implements AccessPolicy {

    /** What the policy needs to know about one door, read once per request. */
    public record GateView(int doorId, String name, AnimationState state, boolean jammed, boolean destroyed,
                           boolean allowPassThrough, boolean siegeLocked, boolean siegeCarries) {
        public GateView {
            Objects.requireNonNull(state, "state");
        }

        /** "the West Gate", or "the gate" when unnamed. */
        public String displayName() {
            return name == null || name.isBlank() ? "the gate" : "the " + name.trim();
        }
    }

    /** Port: the current state of a door by id (paper: {@code GateManager.getGate(id)}). */
    @FunctionalInterface
    public interface GateState {
        Optional<GateView> gate(int doorId);
    }

    /** Port: may this player pass a closed pass-through door (paper: {@code GatePassThroughRules.canPass}). */
    @FunctionalInterface
    public interface PassRule {
        boolean canPass(int doorId);
    }

    public static final String PASS_THROUGH_HINT = "right-click %s to pass";
    public static final String SIEGE_HINT = "%s is locked for the siege; walk up to it to be carried through";

    private final GateState gates;
    private final PassRule passRule;
    private final Map<Integer, EdgeVerdict> byDoor = new HashMap<>();

    public GateAvailability(GateState gates, PassRule passRule) {
        this.gates = Objects.requireNonNull(gates, "gates");
        this.passRule = Objects.requireNonNull(passRule, "passRule");
    }

    @Override
    public EdgeVerdict check(RoadEdge edge) {
        EdgeVerdict result = EdgeVerdict.open();
        for (int doorId : edge.gateDoorIds()) {
            EdgeVerdict v = byDoor.computeIfAbsent(doorId, this::decide);
            if (v.isBlocked()) {
                return v;
            }
            result = EdgeVerdict.stricter(result, v);
        }
        return result;
    }

    /** The verdict for one door (uncached). */
    public EdgeVerdict decide(int doorId) {
        Optional<GateView> view = gates.gate(doorId);
        if (view.isEmpty()) {
            return EdgeVerdict.open();
        }
        GateView g = view.get();
        EdgeVerdict.Cause cause = EdgeVerdict.Cause.gate(doorId, g.displayName());
        if (g.destroyed()) {
            return EdgeVerdict.open();
        }
        if (g.state() == AnimationState.OPEN) {
            return EdgeVerdict.open();
        }
        if (g.state() == AnimationState.OPENING) {
            return EdgeVerdict.blocked(g.displayName() + " is opening", cause);
        }
        if (g.state() == AnimationState.CLOSING) {
            return EdgeVerdict.blocked(g.displayName() + " is closing", cause);
        }
        if (g.jammed()) {
            return EdgeVerdict.blocked(g.displayName() + " is jammed", cause);
        }
        if (g.siegeLocked()) {
            return g.siegeCarries()
                ? EdgeVerdict.passThrough(String.format(SIEGE_HINT, g.displayName()), cause)
                : EdgeVerdict.blocked(g.displayName() + " is locked for a siege", cause);
        }
        if (g.allowPassThrough() && passRule.canPass(doorId)) {
            return EdgeVerdict.passThrough(String.format(PASS_THROUGH_HINT, g.displayName()), cause);
        }
        return EdgeVerdict.blocked(g.displayName() + " is closed", cause);
    }
}
