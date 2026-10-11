package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.DistrictDto;
import net.knightsandkings.knk.api.dto.DistrictListDto;
import net.knightsandkings.knk.api.dto.DistrictListDtoPagedResultDto;
import net.knightsandkings.knk.api.dto.DistrictStreetDto;
import net.knightsandkings.knk.api.dto.DistrictStructureDto;
import net.knightsandkings.knk.api.dto.DistrictTownDto;
import net.knightsandkings.knk.api.dto.LocationDto;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.districts.DistrictDetail;
import net.knightsandkings.knk.core.domain.districts.DistrictSummary;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Mapper for converting district DTOs to domain records.
 * Domain records are a subset of DTO fields (only confirmed-by-API).
 */
public class DistrictsMapper {
    
    /**
     * Maps a list DTO response to domain Page<DistrictSummary>.
     */
    public static Page<DistrictSummary> mapPagedList(DistrictListDtoPagedResultDto dto) {
        List<DistrictSummary> items = (dto.items() != null)
            ? dto.items().stream()
                .map(DistrictsMapper::mapSummary)
                .collect(Collectors.toList())
            : Collections.emptyList();
        
        return new Page<>(items, dto.totalCount(), dto.pageNumber(), dto.pageSize());
    }
    
    /**
     * Maps a single DistrictListDto to DistrictSummary.
     */
    public static DistrictSummary mapSummary(DistrictListDto dto) {
        return new DistrictSummary(
            dto.id(),
            dto.name(),
            dto.description(),
            dto.wgRegionId(),
            dto.townId(),
            dto.townName(),
            dto.worldName()
        );
    }
    
    /**
     * Maps a full DistrictDto to DistrictDetail.
     */
    public static DistrictDetail mapDetail(DistrictDto dto) {
        return new DistrictDetail(
            dto.id(),
            dto.name(),
            dto.description(),
            toOffsetUtc(dto.createdAt()),
            dto.allowEntry(),
            dto.allowExit(),
            dto.wgRegionId(),
            dto.locationId(),
            mapLocation(dto.location()),
            dto.townId(),
            dto.streetIds(),
            mapTown(dto.town()),
            mapStreets(dto.streets()),
            mapStructures(dto.structures())
        );
    }

    /**
     * Converts API LocalDateTime (no zone) to OffsetDateTime using UTC.
     * TODO: Confirm timezone semantics with API contract; adjust if needed.
     */
    private static OffsetDateTime toOffsetUtc(LocalDateTime dt) {
        return dt == null ? null : dt.atOffset(ZoneOffset.UTC);
    }
    
    private static DistrictDetail.Location mapLocation(LocationDto dto) {
        if (dto == null) {
            return null;
        }
        return new DistrictDetail.Location(
            dto.id(),
            dto.name(),
            dto.x(),
            dto.y(),
            dto.z(),
            dto.yaw(),
            dto.pitch(),
            dto.world()
        );
    }
    
    private static DistrictDetail.Town mapTown(DistrictTownDto dto) {
        if (dto == null) {
            return null;
        }
        return new DistrictDetail.Town(
            dto.id(),
            dto.name(),
            dto.description(),
            dto.allowEntry(),
            dto.allowExit(),
            dto.wgRegionId()
        );
    }
    
    private static List<DistrictDetail.Street> mapStreets(List<DistrictStreetDto> dtos) {
        if (dtos == null) {
            return null;
        }
        return dtos.stream()
            .map(dto -> new DistrictDetail.Street(dto.id(), dto.name()))
            .collect(Collectors.toList());
    }
    
    private static List<DistrictDetail.Structure> mapStructures(List<DistrictStructureDto> dtos) {
        if (dtos == null) {
            return null;
        }
        return dtos.stream()
            .map(dto -> new DistrictDetail.Structure(
                dto.id(),
                dto.name(),
                dto.description(),
                dto.houseNumber(),
                dto.streetId()
            ))
            .collect(Collectors.toList());
    }
}
