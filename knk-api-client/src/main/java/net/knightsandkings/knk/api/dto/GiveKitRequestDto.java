package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body for POST api/Kits/{id}/give - actorUserId is deliberately NOT a field here (DESIGN.md
 * §4.6): the API takes it from the caller's JWT, or from the X-Acting-User-Id header on a request
 * carrying the plugin's API key (KNG-22). Mirrors knk-web-api's {@code GiveKitRequestDto}.
 */
public record GiveKitRequestDto(@JsonProperty("targetUserId") int targetUserId) {}
