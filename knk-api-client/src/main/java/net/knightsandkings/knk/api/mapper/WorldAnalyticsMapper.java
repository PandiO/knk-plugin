package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.WorldAnalyticsDtos;
import net.knightsandkings.knk.core.domain.analytics.WorldAnalyticsBatch;
import net.knightsandkings.knk.core.ports.api.WorldAnalyticsApi;

/** World analytics domain ↔ wire (KNG-34 link 7). */
public final class WorldAnalyticsMapper {

    private WorldAnalyticsMapper() {
    }

    public static WorldAnalyticsDtos.Batch toDto(WorldAnalyticsBatch batch, String serverName) {
        return new WorldAnalyticsDtos.Batch(
            batch.batchId().toString(),
            serverName,
            batch.windowStart().toString(),
            batch.movementCells().stream()
                .map(c -> new WorldAnalyticsDtos.MovementCell(c.world(), c.cellSize(), c.cellX(), c.cellZ(), c.samples()))
                .toList(),
            batch.menuSteps().stream()
                .map(s -> new WorldAnalyticsDtos.MenuStep(s.menuKey(), s.step(), s.outcome(), s.count()))
                .toList(),
            batch.domainInteractions().stream()
                .map(d -> new WorldAnalyticsDtos.DomainInteraction(d.domainId(), d.regionId(), d.kind(), d.count(), d.uniquePlayers()))
                .toList()
        );
    }

    public static WorldAnalyticsApi.BatchResult fromDto(WorldAnalyticsDtos.BatchResult dto) {
        if (dto == null) {
            return new WorldAnalyticsApi.BatchResult(false, 0, 0);
        }
        return new WorldAnalyticsApi.BatchResult(dto.duplicate(), dto.accepted(), dto.rejected() == null ? 0 : dto.rejected().size());
    }
}
