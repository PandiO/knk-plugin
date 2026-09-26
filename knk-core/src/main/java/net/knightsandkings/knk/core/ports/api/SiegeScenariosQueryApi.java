package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeReadiness;

import java.util.concurrent.CompletableFuture;

/**
 * Read-side port for siege scenarios (Siege Phase 4). Resolved scenarios reach the runtime only
 * through {@link SiegeLobbiesQueryApi#getRuntimeConfig()}; the API has no "resolved scenario by id"
 * endpoint. What is left per scenario is readiness, for admin diagnostics (e.g. explaining why a
 * rotation scenario was skipped).
 */
public interface SiegeScenariosQueryApi {
    /**
     * {@code GET /api/SiegeScenarios/{id}/readiness}, including the spatial checks when the plugin's
     * region endpoint is reachable. Completes with null when the scenario doesn't exist (404).
     */
    CompletableFuture<KnkSiegeReadiness> getReadiness(int scenarioId);
}
