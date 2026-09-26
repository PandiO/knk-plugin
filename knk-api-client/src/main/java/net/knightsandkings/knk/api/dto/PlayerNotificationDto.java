package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Maps to PlayerNotificationDto from knk-web-api - GET /api/PlayerNotifications/pending. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlayerNotificationDto(
    @JsonProperty("id") long id,
    @JsonProperty("userId") int userId,
    @JsonProperty("uuid") String uuid,
    @JsonProperty("username") String username,
    @JsonProperty("type") String type,
    @JsonProperty("titleChange") TitleChangeResultDto titleChange,
    @JsonProperty("payment") net.knightsandkings.knk.api.dto.currency.CurrencyDtos.PaymentNotificationDto payment
) {}
