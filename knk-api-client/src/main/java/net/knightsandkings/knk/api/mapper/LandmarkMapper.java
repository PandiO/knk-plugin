package net.knightsandkings.knk.api.mapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.knightsandkings.knk.api.dto.LandmarkDto;
import net.knightsandkings.knk.api.dto.LocationDto;
import net.knightsandkings.knk.core.domain.landmarks.KnkLandmark;
import net.knightsandkings.knk.core.domain.landmarks.LandmarkWrite;
import net.knightsandkings.knk.core.navigation.model.Landmark;

/** {@link LandmarkDto} to and from the core records (PandiO/knk-workspace#708). Null-safe. */
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
        String domainName = dto.domainName() != null ? dto.domainName() : dto.domain() != null ? dto.domain().name() : null;
        String domainType = dto.domain() != null ? dto.domain().domainType() : null;
        return new KnkLandmark(dto.id(), dto.name(), locationId, world,
            value(location == null ? null : location.x()), value(location == null ? null : location.y()),
            value(location == null ? null : location.z()),
            location == null || location.yaw() == null ? 0f : location.yaw(),
            location == null || location.pitch() == null ? 0f : location.pitch(),
            dto.domainId(), domainName, domainType, Landmark.Extent.parse(dto.extentKind()), blankToNull(dto.regionId()),
            dto.radius(), dto.preference() == null ? 1.0 : dto.preference(),
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

    /** The request body; the extent as the API's word ("None", "Region", "Radius"). */
    public static LandmarkDto.Write toWrite(LandmarkWrite write) {
        LocationDto location = null;
        if (write.pin() != null) {
            LandmarkWrite.Pin pin = write.pin();
            location = new LocationDto(pin.locationId(), write.name(), pin.x(), pin.y(), pin.z(), pin.yaw(), pin.pitch(),
                pin.world());
        }
        return new LandmarkDto.Write(write.name(), location, write.domainId(),
            write.extent() == null ? null : apiWord(write.extent()), write.regionId(), write.radius(), write.navigationEnabled());
    }

    static String apiWord(Landmark.Extent extent) {
        String lower = extent.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static double value(Double d) {
        return d == null ? 0 : d;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
