package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One proposal item ({@code TileProposal.Item}, plan §5.7) as stored in the API's opaque
 * {@code items}/{@code rejected} arrays. {@code kind} is the {@code TileProposal.Kind} name
 * ({@code EDGE_ADDED} …); {@code summary} is the item's one-line description, for the web app's later
 * review page (B5).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoadProposalItemDto(
    @JsonProperty("n") int n,
    @JsonProperty("kind") String kind,
    @JsonProperty("summary") String summary,
    @JsonProperty("edgeId") Integer edgeId,
    @JsonProperty("from") RoadProposalEndDto from,
    @JsonProperty("to") RoadProposalEndDto to,
    @JsonProperty("geometry") int[][] geometry,
    @JsonProperty("before") int[][] before,
    @JsonProperty("length") Double length,
    @JsonProperty("avgWidth") Double avgWidth,
    @JsonProperty("profileId") Integer profileId,
    @JsonProperty("gateDoorIds") List<Integer> gateDoorIds,
    @JsonProperty("domainIds") List<Integer> domainIds,
    @JsonProperty("regionIds") List<String> regionIds,
    @JsonProperty("node") RoadProposalEndDto node,
    @JsonProperty("target") int[] target,
    @JsonProperty("note") String note,
    @JsonProperty("lockedNodeIds") List<Integer> lockedNodeIds
) {}
