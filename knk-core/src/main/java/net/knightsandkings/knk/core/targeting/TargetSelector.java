package net.knightsandkings.knk.core.targeting;

import java.util.List;
import java.util.Optional;

/**
 * Utility-based target selection with the two stability controls of NPC platform DESIGN §5a: hard eligibility first,
 * then preference × proximity, a minimum commitment to the current target and a switch margin after it.
 *
 * <p>Players are candidates only within the policy's aggro radius of the actor and, while chased, only while they
 * stay within the leash distance of the actor's anchor (Siege Survival DESIGN §4). With {@code pursueDirectly}
 * (DESIGN §3c rule 2: the last enemies run straight at the players) both limits are lifted for players.
 */
public final class TargetSelector {

    /** A scored, eligible candidate. */
    public record Scored(TargetCandidate candidate, double score, double distance) {
    }

    private final TargetPolicy policy;

    public TargetSelector(TargetPolicy policy) {
        this.policy = policy == null ? TargetPolicy.defaults() : policy;
    }

    public TargetPolicy policy() {
        return policy;
    }

    /**
     * Decides the actor's target for this tick.
     *
     * @param candidates     every candidate the consumer knows, eligibility resolved
     * @param current        the current commitment (empty = none)
     * @param actorX         the actor's position
     * @param anchorX        the leash anchor (the actor's structural target or spawn), used for the player leash
     * @param pursueDirectly lifts aggro radius and leash for players (last-enemies rule)
     * @param nowMillis      the time
     * @return the target to hold now: the current one while committed and still eligible, a better one after the
     * commitment when it beats the margin, or empty when nothing is eligible
     */
    public Optional<Scored> select(List<TargetCandidate> candidates, Optional<TargetCommitment> current,
                                   double actorX, double actorY, double actorZ,
                                   double anchorX, double anchorY, double anchorZ,
                                   boolean pursueDirectly, long nowMillis) {
        Scored best = null;
        Scored held = null;
        String heldId = current.map(TargetCommitment::targetId).orElse(null);
        for (TargetCandidate candidate : candidates) {
            if (!candidate.eligible() || policy.preference(candidate.kind()) <= 0) continue;
            double distance = candidate.distanceTo(actorX, actorY, actorZ);
            if (candidate.kind() == TargetKind.PLAYER && !pursueDirectly) {
                if (distance > policy.aggroRadius()) continue;
                double fromAnchor = candidate.distanceTo(anchorX, anchorY, anchorZ);
                if (policy.leashDistance() > 0 && fromAnchor > policy.leashDistance()) continue;
            }
            Scored scored = new Scored(candidate, policy.score(candidate.kind(), distance), distance);
            if (candidate.id().equals(heldId)) held = scored;
            if (best == null || scored.score() > best.score()
                    || (scored.score() == best.score() && scored.distance() < best.distance())) {
                best = scored;
            }
        }
        if (held == null) {
            return Optional.ofNullable(best); // nothing held, or the held target failed a hard rule: released at once
        }
        if (best == null || best.candidate().id().equals(held.candidate().id())) {
            return Optional.of(held);
        }
        TargetCommitment commitment = current.get();
        if (!commitment.expired(nowMillis, policy)) {
            return Optional.of(held); // rule 3: ordinary score changes don't replace it before the commitment ends
        }
        // rule 4: after the commitment a challenger must beat the current score by the margin; expiry alone never switches
        double threshold = held.score() * (1 + policy.switchMargin());
        return Optional.of(best.score() > threshold ? best : held);
    }
}
