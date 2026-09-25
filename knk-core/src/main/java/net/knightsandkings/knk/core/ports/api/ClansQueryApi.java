package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.domain.clan.KnkBannerDesign;
import net.knightsandkings.knk.core.domain.clan.KnkClan;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Read-side port for clans and banner designs (Siege Phase 1,
 * docs/specs/siege-minigame/IMPLEMENTATION_PLAN.md). Clans come back with their full banner.
 * Lookups complete with null when the API answers 404.
 */
public interface ClansQueryApi {
    CompletableFuture<List<KnkClan>> listClans();

    CompletableFuture<KnkClan> getClanById(int id);

    CompletableFuture<KnkClan> getDefaultClanForTown(int townId);

    CompletableFuture<KnkBannerDesign> getBannerDesignById(int id);
}
