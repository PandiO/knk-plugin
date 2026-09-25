package net.knightsandkings.knk.core.domain.siege;

import java.util.List;

/**
 * A scenario's readiness (API {@code GET /api/siege-scenarios/{id}/readiness}, DESIGN §3.9).
 *
 * @param spatialChecksRun false when the WorldGuard-region checks couldn't run (see the warnings)
 */
public record KnkSiegeReadiness(
        int scenarioId,
        boolean ready,
        boolean spatialChecksRun,
        List<KnkSiegeReadinessIssue> errors,
        List<KnkSiegeReadinessIssue> warnings
) {
    public KnkSiegeReadiness {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
