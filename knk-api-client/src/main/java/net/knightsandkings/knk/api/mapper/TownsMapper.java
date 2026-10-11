package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.LocationDto;
import net.knightsandkings.knk.api.dto.TownDistrictDto;
import net.knightsandkings.knk.api.dto.TownDto;
import net.knightsandkings.knk.api.dto.TownListDto;
import net.knightsandkings.knk.api.dto.TownListDtoPagedResultDto;
import net.knightsandkings.knk.api.dto.TownStreetDto;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.towns.TownDetail;
import net.knightsandkings.knk.core.domain.towns.TownSummary;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Mapper for converting town DTOs to domain records.
 * Domain records are a subset of DTO fields (only confirmed-by-API).
 */
public class TownsMapper {
    
    /**
     * Maps a list DTO response to domain Page<TownSummary>.
     */
    public static Page<TownSummary> mapPagedList(TownListDtoPagedResultDto dto) {
        List<TownSummary> items = (dto.items() != null)
            ? dto.items().stream()
                .map(TownsMapper::mapSummary)
                .collect(Collectors.toList())
            : Collections.emptyList();
        
        return new Page<>(items, dto.totalCount(), dto.pageNumber(), dto.pageSize());
    }
    
    /**
     * Maps a single TownListDto to TownSummary.
     */
    public static TownSummary mapSummary(TownListDto dto) {
        return new TownSummary(dto.id(), dto.name(), dto.description(), dto.wgRegionId(), dto.worldName());
    }
    
    /**
     * Maps a full TownDto to TownDetail.
     */
    public static TownDetail mapDetail(TownDto dto) {
        return new TownDetail(
            dto.id(),
            dto.name(),
            dto.description(),
            toOffsetUtc(dto.createdAt()),
            dto.allowEntry(),
            dto.allowExit(),
            dto.wgRegionId(),
            dto.locationId(),
            mapLocation(dto.location()),
            dto.streetIds(),
            mapStreets(dto.streets()),
            dto.districtIds(),
            mapDistricts(dto.districts())
        );
    }

    /**
     * Converts API LocalDateTime (no zone) to OffsetDateTime using UTC.
     * TODO: Confirm timezone semantics with API contract; adjust if needed.
     */
    private static OffsetDateTime toOffsetUtc(LocalDateTime dt) {
        return dt == null ? null : dt.atOffset(ZoneOffset.UTC);
    }
    
    private static TownDetail.Location mapLocation(LocationDto dto) {
        if (dto == null) {
            return null;
        }
        return new TownDetail.Location(
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
    
    private static List<TownDetail.TownStreet> mapStreets(List<TownStreetDto> dtos) {
        if (dtos == null) {
            return null;
        }
        return dtos.stream()
            .map(dto -> new TownDetail.TownStreet(dto.id(), dto.name()))
            .collect(Collectors.toList());
    }
    
    private static List<TownDetail.TownDistrict> mapDistricts(List<TownDistrictDto> dtos) {
        if (dtos == null) {
            return null;
        }
        return dtos.stream()
            .map(dto -> new TownDetail.TownDistrict(
                dto.id(),
                dto.name(),
                dto.description(),
                dto.allowEntry(),
                dto.allowExit(),
                dto.wgRegionId()
            ))
            .collect(Collectors.toList());
    }
}
