package net.knightsandkings.knk.core.targeting;

import java.util.Objects;

/**
 * The target an actor currently holds (§5a rule 3): what it is, how it scored when adopted and since when.
 *
 * @param sinceMillis when the target was adopted; route replanning never resets it (rule 3)
 */
public record TargetCommitment(String targetId, TargetKind kind, double score, long sinceMillis) {

    public TargetCommitment {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(kind, "kind");
    }

    public boolean expired(long nowMillis, TargetPolicy policy) {
        return nowMillis - sinceMillis >= policy.minCommitmentMillis();
    }
}
