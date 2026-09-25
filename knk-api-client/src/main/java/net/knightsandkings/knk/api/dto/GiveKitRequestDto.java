package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body for POST api/Kits/{id}/give - actorUserId is deliberately NOT a field here; it's
 * resolved server-side from the authenticated caller's JWT claims (DESIGN.md §4.6), never
 * client-supplied. Mirrors knk-web-api's {@code GiveKitRequestDto}.
 */
public record GiveKitRequestDto(@JsonProperty("targetUserId") int targetUserId) {}
