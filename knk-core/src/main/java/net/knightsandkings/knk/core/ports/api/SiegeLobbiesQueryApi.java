package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeRuntimeConfig;

import java.util.concurrent.CompletableFuture;

/**
 * Read-side port for siege lobbies (Siege Phase 4, docs/specs/siege-minigame/DESIGN.md §5.1,
 * §11.2). The plugin reads lobbies only through the runtime-config payload: the global
 * configuration plus every enabled lobby with its fully resolved, ready scenarios. Go through
 * {@code SiegeDataAccess} (cache-first) rather than calling this directly.
 */
public interface SiegeLobbiesQueryApi {
    /** {@code GET /api/SiegeLobbies/runtime-config} (also served as /api/siege-lobbies/runtime-config). */
    CompletableFuture<KnkSiegeRuntimeConfig> getRuntimeConfig();
}
