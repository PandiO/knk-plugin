package net.knightsandkings.knk.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import net.knightsandkings.knk.api.serialization.LenientOffsetDateTimeDeserializer;

/**
 * Maps to knk-web-api's RoadTileDto (road navigation, KNG-27; plan D4 {@code version} = ETag;
 * {@code state} {@code Detected | Curated} and {@code curatedAt}, rev. 6 plan §5.7).
 */
public record RoadTileDto(
    @JsonProperty("id") int id,
    @JsonProperty("world") String world,
    @JsonProperty("tileX") int tileX,
    @JsonProperty("tileZ") int tileZ,
    @JsonProperty("version") int version,
    @JsonProperty("builtAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime builtAt,
    @JsonProperty("builderVersion") int builderVersion,
    @JsonProperty("dirty") boolean dirty,
    @JsonProperty("cellCount") int cellCount,
    @JsonProperty("nodeCount") int nodeCount,
    @JsonProperty("edgeCount") int edgeCount,
    @JsonProperty("levelCount") int levelCount,
    @JsonProperty("warnings") List<String> warnings,
    @JsonProperty("state") String state,
    @JsonProperty("curatedAt") @JsonDeserialize(using = LenientOffsetDateTimeDeserializer.class) OffsetDateTime curatedAt
) {}
