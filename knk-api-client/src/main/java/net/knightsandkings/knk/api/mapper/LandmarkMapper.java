package net.knightsandkings.knk.api.mapper;

import java.util.ArrayList;
import java.util.List;

import net.knightsandkings.knk.api.dto.LandmarkDto;
import net.knightsandkings.knk.api.dto.LocationDto;
import net.knightsandkings.knk.core.domain.landmarks.KnkLandmark;
import net.knightsandkings.knk.core.domain.landmarks.LandmarkAreaKind;
import net.knightsandkings.knk.core.domain.landmarks.LandmarkWrite;

/** {@link LandmarkDto} to and from the core records (PandiO/knk-workspace#708, #985). Null-safe. */
public final class LandmarkMapper {

    private LandmarkMapper() {
    }

    /** Null for null or a row without id or name. The world is the Location's, else the flat {@code world}. */
    public static KnkLandmark toCore(LandmarkDto dto) {
        if (dto == null || dto.id() == null || dto.name() == null) {
            return null;
        }
        LocationDto location = dto.location();
        String world = location != null && location.world() != null ? location.world() : dto.world();
        Integer locationId = dto.locationId() != null ? dto.locationId() : location != null ? location.id() : null;
        LandmarkDto.Domain parent = dto.parentDomain();
        String parentName = dto.parentDomainName() != null ? dto.parentDomainName() : parent != null ? parent.name() : null;
        Integer parentId = dto.parentDomainId() != null ? dto.parentDomainId() : parent != null ? parent.id() : null;
        return new KnkLandmark(dto.id(), dto.name(), locationId, world,
            value(location == null ? null : location.x()), value(location == null ? null : location.y()),
            value(location == null ? null : location.z()),
            location == null || location.yaw() == null ? 0f : location.yaw(),
            location == null || location.pitch() == null ? 0f : location.pitch(),
            parentId, parentName, parent == null ? null : parent.domainType(), parent == null ? null : parent.wgRegionId(),
            LandmarkAreaKind.parse(dto.areaKind()), dto.wgRegionId(), dto.subRegionIds(),
            Boolean.TRUE.equals(dto.showEnterLeaveMessages()), dto.preference() == null ? 1.0 : dto.preference(),
            dto.navigationEnabled() == null || dto.navigationEnabled());
    }

    public static List<KnkLandmark> toCore(List<LandmarkDto> dtos) {
        List<KnkLandmark> out = new ArrayList<>();
        if (dtos != null) {
            for (LandmarkDto dto : dtos) {
                KnkLandmark landmark = toCore(dto);
                if (landmark != null) {
                    out.add(landmark);
                }
            }
        }
        return out;
    }

    /** The request body; the area as the API's word ("None", "OwnRegion", "LinkedDomain"). */
    public static LandmarkDto.Write toWrite(LandmarkWrite write) {
        LocationDto location = null;
        if (write.pin() != null) {
            LandmarkWrite.Pin pin = write.pin();
            location = new LocationDto(pin.locationId(), write.name(), pin.x(), pin.y(), pin.z(), pin.yaw(), pin.pitch(),
                pin.world());
        }
        return new LandmarkDto.Write(write.name(), location, write.parentDomainId(),
            write.areaKind() == null ? null : write.areaKind().apiWord(), write.wgRegionId(), write.navigationEnabled());
    }

    private static double value(Double d) {
        return d == null ? 0 : d;
    }
}
