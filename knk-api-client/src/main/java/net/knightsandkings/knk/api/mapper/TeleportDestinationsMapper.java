package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.TeleportDestinationDto;
import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;

/** knk-web-api teleport destination JSON → knk-core {@link KnkTeleportDestination}. */
public final class TeleportDestinationsMapper {

    private TeleportDestinationsMapper() {
    }

    /** Null for a row the plugin can't use (no id, name or location). */
    public static KnkTeleportDestination map(TeleportDestinationDto dto) {
        if (dto == null || dto.domainId() == null || dto.name() == null || dto.location() == null) {
            return null;
        }
        TeleportDestinationDto.Location location = dto.location();
        return new KnkTeleportDestination(
            dto.domainId(),
            dto.name(),
            dto.domainType(),
            location.world(),
            orZero(location.x()),
            orZero(location.y()),
            orZero(location.z()),
            location.yaw() != null ? location.yaw() : 0f,
            location.pitch() != null ? location.pitch() : 0f,
            dto.priceGems() != null ? dto.priceGems() : 0,
            dto.minTitleName(),
            dto.minPremiumTierName(),
            Boolean.TRUE.equals(dto.requiresDiscovery()),
            Boolean.TRUE.equals(dto.available()),
            Boolean.TRUE.equals(dto.requirementsMet()),
            Boolean.TRUE.equals(dto.canAfford()),
            dto.lockCode(),
            dto.lockReason()
        );
    }

    private static double orZero(Double value) {
        return value != null ? value : 0d;
    }
}
