package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.TeleportDestinationDto;
import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;

/** knk-web-api teleport destination JSON → knk-core {@link KnkTeleportDestination}. */
public final class TeleportDestinationsMapper {

    private TeleportDestinationsMapper() {
    }

    /** Null for a row the plugin can't use (no id, name or location). */
    public static KnkTeleportDestination map(TeleportDestinationDto dto) {
        String name = dto != null ? displayName(dto.name()) : null;
        if (dto == null || dto.domainId() == null || name == null || dto.location() == null) {
            return null;
        }
        TeleportDestinationDto.Location location = dto.location();
        return new KnkTeleportDestination(
            dto.domainId(),
            name,
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
            dto.lockReason(),
            dto.priceCoins() != null ? dto.priceCoins() : 0,
            dto.priceExperience() != null ? dto.priceExperience() : 0
        );
    }

    /**
     * A domain name as players see and type it: line breaks, tabs and other control characters
     * become spaces, runs of whitespace collapse, ends are trimmed (a name stored with a trailing
     * line break put the "." of "You teleported to ...". on its own chat line - KNG-42 smoke test).
     * Null when nothing is left.
     */
    static String displayName(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}\\s]+", " ").strip();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static double orZero(Double value) {
        return value != null ? value : 0d;
    }
}
