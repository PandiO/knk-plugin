package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One end of a proposal item ({@code TileProposal.End}): {@code nodeId} 0 is a node the stored graph
 * lacks. The proposal item format belongs to the plugin; the API stores it as opaque JSON (plan §5.7 D5).
 */
public record RoadProposalEndDto(
    @JsonProperty("nodeId") int nodeId,
    @JsonProperty("x") int x,
    @JsonProperty("y") int y,
    @JsonProperty("z") int z,
    @JsonProperty("kind") String kind
) {}
