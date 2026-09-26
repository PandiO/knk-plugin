package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.ClanDtos.BannerDesignDto;
import net.knightsandkings.knk.api.dto.ClanDtos.BannerLayerDto;
import net.knightsandkings.knk.api.dto.ClanDtos.ClanDto;
import net.knightsandkings.knk.core.domain.clan.KnkBannerDesign;
import net.knightsandkings.knk.core.domain.clan.KnkBannerLayer;
import net.knightsandkings.knk.core.domain.clan.KnkClan;

import java.util.List;
import java.util.Objects;

public final class ClanMapper {
    private ClanMapper() {}

    public static KnkClan toCore(ClanDto dto) {
        if (dto == null) return null;
        return new KnkClan(
                orZero(dto.id()),
                dto.name(),
                Boolean.TRUE.equals(dto.isNpc()),
                dto.chatColor() != null ? dto.chatColor() : "WHITE",
                orZero(dto.bannerDesignId()),
                toCore(dto.bannerDesign()),
                dto.defaultForTownId(),
                dto.defaultForTownName()
        );
    }

    public static KnkBannerDesign toCore(BannerDesignDto dto) {
        if (dto == null) return null;
        List<KnkBannerLayer> layers = dto.layers() == null ? List.of() : dto.layers().stream()
                .filter(Objects::nonNull)
                .map(ClanMapper::toCore)
                .toList();
        // KnkBannerDesign orders the layers itself.
        return new KnkBannerDesign(orZero(dto.id()), dto.name(), dto.baseColor(), layers);
    }

    static KnkBannerLayer toCore(BannerLayerDto dto) {
        return new KnkBannerLayer(orZero(dto.id()), orZero(dto.sortOrder()), dto.patternKey(), dto.color());
    }

    private static int orZero(Integer value) {
        return value != null ? value : 0;
    }
}
