package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to knk-web-api's {@code LandmarkReadDto} ({@code GET api/landmarks}, {@code GET api/landmarks/{id}} and the
 * answers of create and update), since PandiO/knk-workspace#985 with the landmark-domain field names (DESIGN § 6.2):
 * {@code areaKind} ("None", "OwnRegion", "LinkedDomain"), {@code wgRegionId}, {@code subRegionIds},
 * {@code parentDomainId} and {@code parentDomain}. The API's #708 projection ({@code domainId}, {@code extentKind},
 * {@code regionId}, {@code radius}) is not read: a later change drops it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LandmarkDto(
    @JsonProperty("id") Integer id,
    @JsonProperty("name") String name,
    @JsonProperty("locationId") Integer locationId,
    @JsonProperty("location") LocationDto location,
    @JsonProperty("world") String world,
    @JsonProperty("areaKind") String areaKind,
    @JsonProperty("wgRegionId") String wgRegionId,
    @JsonProperty("subRegionIds") List<String> subRegionIds,
    @JsonProperty("parentDomainId") Integer parentDomainId,
    @JsonProperty("parentDomain") Domain parentDomain,
    @JsonProperty("parentDomainName") String parentDomainName,
    @JsonProperty("showEnterLeaveMessages") Boolean showEnterLeaveMessages,
    @JsonProperty("preference") Double preference,
    @JsonProperty("navigationEnabled") Boolean navigationEnabled,
    @JsonProperty("kind") String kind
) {

    /** The API's {@code LandmarkDomainNavDto}: the parent as a landmark nests it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Domain(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") String name,
        @JsonProperty("domainType") String domainType,
        @JsonProperty("worldName") String worldName,
        @JsonProperty("wgRegionId") String wgRegionId
    ) {}

    /**
     * The body of {@code POST api/landmarks} and {@code PUT api/landmarks/{id}} ({@code LandmarkCreateDto} /
     * {@code LandmarkUpdateDto}). Nulls are left out, except {@code parentDomainId}, which an update takes as sent
     * (null unlinks), so it is always written.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Write(
        @JsonProperty("name") String name,
        @JsonProperty("location") LocationDto location,
        @JsonProperty("parentDomainId") @JsonInclude(JsonInclude.Include.ALWAYS) Integer parentDomainId,
        @JsonProperty("areaKind") String areaKind,
        @JsonProperty("wgRegionId") String wgRegionId,
        @JsonProperty("navigationEnabled") Boolean navigationEnabled
    ) {}
}
