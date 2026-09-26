package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to knk-web-api's TeleportAuditDto - POST /api/users/{id}/teleport-audit
 * (docs/specs/teleport/DESIGN.md §3.10). The staff member travels in the X-Acting-User-Id header,
 * not in the body.
 */
public record TeleportAuditDto(
    @JsonProperty("kind") String kind,
    @JsonProperty("subjectUserId") int subjectUserId,
    @JsonProperty("visitedUserId") Integer visitedUserId,
    @JsonProperty("from") Point from,
    @JsonProperty("to") Point to,
    @JsonProperty("silent") boolean silent,
    @JsonProperty("reason") String reason,
    // "command" (a staff member in game) or "console"
    @JsonProperty("via") String via
) {
    public record Point(
        @JsonProperty("world") String world,
        @JsonProperty("x") double x,
        @JsonProperty("y") double y,
        @JsonProperty("z") double z
    ) {}
}
