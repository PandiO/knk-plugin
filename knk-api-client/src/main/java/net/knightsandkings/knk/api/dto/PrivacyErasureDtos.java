package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** knk-web-api {@code PrivacyErasureFeedDto} ({@code GET api/privacy/erasures}, PandiO/knk-workspace#184). */
public final class PrivacyErasureDtos {
    private PrivacyErasureDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
        @JsonProperty("erasureId") long erasureId,
        @JsonProperty("userId") int userId,
        @JsonProperty("formerUuid") String formerUuid,
        @JsonProperty("erasedAt") String erasedAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Feed(
        @JsonProperty("items") List<Item> items,
        @JsonProperty("lastId") long lastId,
        @JsonProperty("hasMore") boolean hasMore,
        @JsonProperty("latestId") long latestId
    ) {}
}
