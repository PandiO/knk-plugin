package net.knightsandkings.knk.paper.siege.survival;

import java.util.Optional;

/**
 * Hard target eligibility of Siege Survival (DESIGN §4, "eligibility overrides", §3b ghosts; S68: players, gates and
 * objectives only), evaluated before any scoring. Bukkit-free; the adapter supplies the facts.
 *
 * <p>Standard rules: a <b>player</b> is eligible while alive, in the match and not a ghost. An <b>objective</b> is
 * eligible while not NPC-held and physically reachable without a breach, which v1 approximates as "its own gate has
 * no closed standing door" (a closed objective gate blocks the capture ring behind it by construction). A
 * <b>gate</b> is eligible while it has a closed, standing, damageable door and it is the gate of an objective that is
 * not NPC-held (the breach is on a route to an eligible objective). {@code ALLOW} also makes an area gate with a
 * closed door eligible; {@code DENY} removes an objective or gate from scoring whatever the standard rule says, and
 * a denied gate is not breached on the way to an allowed objective (that objective is then simply unreachable for
 * the mob, which the navigation failure reports). An allowed objective does not allow its gate and vice versa.
 */
public final class TargetEligibilityRules {

    /** Eligible, or the reason it is not. */
    public record Verdict(boolean eligible, String reason) {
        public static final Verdict OK = new Verdict(true, "");

        public static Verdict denied(String reason) {
            return new Verdict(false, reason);
        }
    }

    private TargetEligibilityRules() {
    }

    public static Verdict player(boolean online, boolean inMatch, boolean alive, boolean ghost) {
        if (!online) return Verdict.denied("offline");
        if (!inMatch) return Verdict.denied("not-in-match");
        if (ghost) return Verdict.denied("ghost");
        if (!alive) return Verdict.denied("dead");
        return Verdict.OK;
    }

    /**
     * @param npcHeld        the objective is NPC-held
     * @param gateClosed     the objective has a gate with a closed standing door (empty: no gate)
     * @param hasCapturePoint the capture location is known
     */
    public static Verdict objective(TargetOverrideMode override, boolean npcHeld, Optional<Boolean> gateClosed, boolean hasCapturePoint) {
        if (override == TargetOverrideMode.DENY) return Verdict.denied("denied");
        if (npcHeld) return Verdict.denied("npc-held");
        if (!hasCapturePoint) return Verdict.denied("no-capture-point");
        if (gateClosed.isPresent() && gateClosed.get()) return Verdict.denied("behind-closed-gate");
        return Verdict.OK;
    }

    /**
     * @param closedDoor     the gate has a closed, standing, active door
     * @param damageable     the scenario lets this gate be damaged
     * @param objectiveGate  the gate belongs to an objective
     * @param objectiveNpcHeld that objective is NPC-held (false for an area gate)
     * @param hasStandPoint  an approach point in front of a closed door was found
     */
    public static Verdict gate(TargetOverrideMode override, boolean closedDoor, boolean damageable, boolean objectiveGate,
                               boolean objectiveNpcHeld, boolean hasStandPoint) {
        if (override == TargetOverrideMode.DENY) return Verdict.denied("denied");
        if (!closedDoor) return Verdict.denied("open");
        if (!damageable) return Verdict.denied("not-damageable");
        if (!hasStandPoint) return Verdict.denied("no-stand-point");
        if (override == TargetOverrideMode.ALLOW) return Verdict.OK;
        if (!objectiveGate) return Verdict.denied("area-gate");
        if (objectiveNpcHeld) return Verdict.denied("objective-npc-held");
        return Verdict.OK;
    }
}
