package net.knightsandkings.knk.core.domain.siege;

import java.util.Objects;

/** One scenario in a lobby's rotation; {@code weight} (at least 1) biases candidate and random draws. */
public record KnkSiegeRotationEntry(int weight, KnkSiegeScenario scenario) {
    public KnkSiegeRotationEntry {
        Objects.requireNonNull(scenario, "scenario");
        if (weight < 1) weight = 1;
    }
}
