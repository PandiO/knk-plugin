package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeGateRecords.LockdownEntry;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeGateRecords.RestoreResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Siege Phase 7a: the persisted half of a match's gate lockdown (DESIGN §8.2, §8.4). The server
 * writes the snapshot before it changes anything, so a crash mid-match is recoverable with
 * {@link #restoreStale()} on the next enable. Never called on the main thread's critical path.
 */
public interface SiegeGatesCommandApi {
    /** {@code POST /api/siege-matches/{id}/gate-lockdown}: snapshot → CurrentSiegeId → overrides. */
    CompletableFuture<Void> lockdown(long matchId, List<LockdownEntry> gates);

    /** {@code POST /api/siege-matches/{id}/gate-restore}: re-applies and deletes the match's snapshots. */
    CompletableFuture<RestoreResult> restore(long matchId);

    /** {@code POST /api/siege-matches/restore-stale-gates}: startup recovery of every leftover snapshot. */
    CompletableFuture<RestoreResult> restoreStale();
}
