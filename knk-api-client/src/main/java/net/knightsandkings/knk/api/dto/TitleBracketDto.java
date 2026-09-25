package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** {@code GET /api/TitleBrackets} row (web-api {@code TitleBracketDto}); male/female names aren't used by the plugin. */
public record TitleBracketDto(
    @JsonProperty("id") int id,
    @JsonProperty("name") String name,
    @JsonProperty("minExperience") int minExperience
) {}
