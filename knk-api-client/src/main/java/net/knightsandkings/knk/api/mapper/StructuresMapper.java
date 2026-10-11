package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.StructureDto;
import net.knightsandkings.knk.api.dto.StructureListDto;
import net.knightsandkings.knk.api.dto.StructureListDtoPagedResultDto;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.structures.StructureDetail;
import net.knightsandkings.knk.core.domain.structures.StructureSummary;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Mapper for converting structure DTOs to domain records.
 */
public class StructuresMapper {

    public static Page<StructureSummary> mapPagedList(StructureListDtoPagedResultDto dto) {
        List<StructureSummary> items = (dto.items() != null)
            ? dto.items().stream().map(StructuresMapper::mapSummary).collect(Collectors.toList())
            : Collections.emptyList();
        return new Page<>(items, dto.totalCount(), dto.pageNumber(), dto.pageSize());
    }

    public static StructureSummary mapSummary(StructureListDto dto) {
        return new StructureSummary(
            dto.id(),
            dto.name(),
            dto.description(),
            dto.wgRegionId(),
            dto.houseNumber(),
            dto.streetId(),
            dto.streetName(),
            dto.districtId(),
            dto.districtName(),
            dto.worldName()
        );
    }

    public static StructureDetail mapDetail(StructureDto dto) {
        return new StructureDetail(
            dto.id(),
            dto.name(),
            dto.description(),
            toOffsetUtc(dto.createdAt()),
            dto.allowEntry(),
            dto.allowExit(),
            dto.wgRegionId(),
            dto.locationId(),
            dto.streetId(),
            dto.districtId(),
            dto.houseNumber()
        );
    }

    private static OffsetDateTime toOffsetUtc(LocalDateTime dt) {
        return dt == null ? null : dt.atOffset(ZoneOffset.UTC);
    }
}
