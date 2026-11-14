package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * knk-web-api {@code PrivacyErasureFeedDto} ({@code GET api/privacy/erasures}, PandiO/knk-workspace#184).
 * <p>
 * Both casings (PandiO/knk-workspace#486): the API's {@code Program.cs} sets {@code PropertyNamingPolicy = null} and,
 * unlike the API's other DTOs, {@code PrivacyErasureFeedDto} and {@code PrivacyErasureFeedItemDto} carry no
 * {@code [JsonPropertyName]}, so the feed arrives in PascalCase ({@code Items}, {@code ErasureId}, {@code LastId}, ...).
 * The camelCase names stay for an API that adds the attributes later. Read with camelCase only, the feed parsed as an
 * empty page with lastId 0, so the catch-up never applied an erasure and {@code erasure-feed.json} never advanced.
 */
public final class PrivacyErasureDtos {
    private PrivacyErasureDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
        @JsonProperty("erasureId") @JsonAlias("ErasureId") long erasureId,
        @JsonProperty("userId") @JsonAlias("UserId") int userId,
        @JsonProperty("formerUuid") @JsonAlias("FormerUuid") String formerUuid,
        @JsonProperty("erasedAt") @JsonAlias("ErasedAt") String erasedAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Feed(
        @JsonProperty("items") @JsonAlias("Items") List<Item> items,
        @JsonProperty("lastId") @JsonAlias("LastId") long lastId,
        @JsonProperty("hasMore") @JsonAlias("HasMore") boolean hasMore,
        @JsonProperty("latestId") @JsonAlias("LatestId") long latestId
    ) {}
}
