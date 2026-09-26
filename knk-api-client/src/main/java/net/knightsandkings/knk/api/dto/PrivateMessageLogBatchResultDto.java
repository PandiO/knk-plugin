package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to PrivateMessageLogBatchResultDto in knk-web-api. */
public record PrivateMessageLogBatchResultDto(
    @JsonProperty("accepted") int accepted,
    @JsonProperty("duplicates") int duplicates
) {}
