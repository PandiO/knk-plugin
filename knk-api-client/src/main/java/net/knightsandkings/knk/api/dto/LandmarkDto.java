package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to knk-web-api's {@code LandmarkReadDto} (off-road navigation phase 5.1, PandiO/knk-workspace#708):
 * {@code GET api/landmarks}, {@code GET api/landmarks/{id}} and the answers of create and update. {@code extentKind}
 * is "None", "Region" or "Radius".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LandmarkDto(
    @JsonProperty("id") Integer id,
    @JsonProperty("name") String name,
    @JsonProperty("locationId") Integer locationId,
    @JsonProperty("location") LocationDto location,
    @JsonProperty("world") String world,
    @JsonProperty("domainId") Integer domainId,
    @JsonProperty("domain") Domain domain,
    @JsonProperty("domainName") String domainName,
    @JsonProperty("extentKind") String extentKind,
    @JsonProperty("regionId") String regionId,
    @JsonProperty("radius") Double radius,
    @JsonProperty("preference") Double preference,
    @JsonProperty("navigationEnabled") Boolean navigationEnabled,
    @JsonProperty("kind") String kind
) {

    /** The API's {@code LandmarkDomainNavDto}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Domain(
        @JsonProperty("id") Integer id,
        @JsonProperty("name") String name,
        @JsonProperty("domainType") String domainType,
        @JsonProperty("worldName") String worldName
    ) {}

    /**
     * The body of {@code POST api/landmarks} and {@code PUT api/landmarks/{id}} ({@code LandmarkCreateDto} /
     * {@code LandmarkUpdateDto}). Nulls are left out, except {@code domainId}, which an update takes as sent (null
     * unlinks), so it is always written.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Write(
        @JsonProperty("name") String name,
        @JsonProperty("location") LocationDto location,
        @JsonProperty("domainId") @JsonInclude(JsonInclude.Include.ALWAYS) Integer domainId,
        @JsonProperty("extentKind") String extentKind,
        @JsonProperty("regionId") String regionId,
        @JsonProperty("radius") Double radius,
        @JsonProperty("navigationEnabled") Boolean navigationEnabled
    ) {}
}
