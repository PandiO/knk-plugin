package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Body of {@code PUT api/road-tiles/{world}/{x}/{z}/state} ({@code Detected | Curated}, plan §5.7 D1). */
public record RoadTileStateDto(@JsonProperty("state") String state) {}
