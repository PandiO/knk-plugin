package net.knightsandkings.knk.api.mapper;

import java.util.List;

import net.knightsandkings.knk.api.dto.DiscoveryGrantDto;
import net.knightsandkings.knk.api.dto.DiscoveryGrantResultDto;
import net.knightsandkings.knk.api.dto.DiscoveryProgressRowDto;
import net.knightsandkings.knk.api.dto.DiscoverySkipDto;
import net.knightsandkings.knk.api.dto.DiscoverySummaryDto;
import net.knightsandkings.knk.api.dto.DiscoveryTypeCountDto;
import net.knightsandkings.knk.api.dto.KnownDiscoveryDto;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrant;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryProgressRow;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySkip;
import net.knightsandkings.knk.core.domain.discovery.DiscoverySummary;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryTypeCount;
import net.knightsandkings.knk.core.domain.discovery.KnownDiscovery;

/** Domain discovery DTOs (knk-web-api's DiscoveryDtos.cs) to knk-core records. */
public final class DiscoveriesMapper {
    private DiscoveriesMapper() {}

    public static DiscoveryGrantResult mapGrantResult(DiscoveryGrantResultDto dto) {
        if (dto == null) {
            return null;
        }
        return new DiscoveryGrantResult(
            dto.granted() == null ? List.of() : dto.granted().stream().map(DiscoveriesMapper::mapGrant).toList(),
            dto.alreadyDiscovered(),
            dto.skipped() == null ? List.of() : dto.skipped().stream().map(DiscoveriesMapper::mapSkip).toList(),
            dto.totalCoins(),
            dto.totalGems(),
            dto.totalExp(),
            dto.totalCoinsBase(),
            dto.totalGemsBase(),
            dto.totalExpBase(),
            UsersMapper.mapRewardMultipliers(dto.coinMultipliers()),
            UsersMapper.mapRewardMultipliers(dto.gemMultipliers()),
            UsersMapper.mapRewardMultipliers(dto.expMultipliers()),
            dto.titleBracketId(),
            dto.newCoins(),
            dto.newGems(),
            dto.newExperiencePoints(),
            UsersMapper.mapTitleChange(dto.titleChange())
        );
    }

    public static DiscoveryGrant mapGrant(DiscoveryGrantDto dto) {
        return new DiscoveryGrant(dto.domainId(), dto.wgRegionId(), dto.name(), dto.domainType(), dto.parentName(),
            dto.source(), dto.coins(), dto.gems(), dto.exp(), dto.coinsBase(), dto.gemsBase(), dto.expBase());
    }

    public static DiscoverySkip mapSkip(DiscoverySkipDto dto) {
        return new DiscoverySkip(dto.key(), dto.reason());
    }

    public static KnownDiscovery mapKnown(KnownDiscoveryDto dto) {
        return new KnownDiscovery(dto.domainId(), dto.wgRegionId());
    }

    public static DiscoveryProgressRow mapProgressRow(DiscoveryProgressRowDto dto) {
        if (dto == null) {
            return null;
        }
        return new DiscoveryProgressRow(dto.domainId(), dto.name(), dto.domainType(), dto.parentName(), dto.discovered(),
            dto.discoveredAt(), dto.coins(), dto.gems(), dto.exp());
    }

    public static DiscoverySummary mapSummary(DiscoverySummaryDto dto) {
        if (dto == null) {
            return null;
        }
        return new DiscoverySummary(
            dto.byType() == null ? List.of() : dto.byType().stream().map(DiscoveriesMapper::mapTypeCount).toList(),
            mapProgressRow(dto.latest()),
            dto.totalDiscovered(),
            dto.totalCoins(),
            dto.totalGems(),
            dto.totalExp()
        );
    }

    private static DiscoveryTypeCount mapTypeCount(DiscoveryTypeCountDto dto) {
        return new DiscoveryTypeCount(dto.domainType(), dto.discovered(), dto.total());
    }
}
