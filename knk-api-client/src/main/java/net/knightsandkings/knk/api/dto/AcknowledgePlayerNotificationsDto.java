package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to AcknowledgePlayerNotificationsDto from knk-web-api - POST /api/PlayerNotifications/acknowledge. */
public record AcknowledgePlayerNotificationsDto(
    @JsonProperty("ids") List<Long> ids
) {}
