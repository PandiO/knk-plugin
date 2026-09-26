package net.knightsandkings.knk.core.domain.siege;

import java.util.List;

/** A rotation scenario the API left out because it isn't ready, with the blocking errors. */
public record KnkSiegeSkippedScenario(int scenarioId, String name, List<KnkSiegeReadinessIssue> errors) {
    public KnkSiegeSkippedScenario {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
