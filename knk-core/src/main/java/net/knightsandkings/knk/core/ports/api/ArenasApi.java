package net.knightsandkings.knk.core.ports.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;

/**
 * Read-side port for duel arenas (arena duels KNG-210, DESIGN.md §4.1-4.2). Go through {@code ArenasDataAccess}
 * (cache-first) rather than calling this directly.
 */
public interface ArenasApi {
    /** {@code GET /api/Arenas}: every arena with its settings, battlegrounds, spectator areas and eligible kits. */
    CompletableFuture<List<KnkArena>> getAll();
}
