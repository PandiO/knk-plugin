package net.knightsandkings.knk.api.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** knk-web-api's admission DTOs ({@code Dtos/AdmissionDtos.cs}, {@code Dtos/PlayerNotificationDtos.cs}). */
public final class AdmissionDtos {
    private AdmissionDtos() {
    }

    /** {@code GET api/Admission/{uuid}?name=}: {@code code} is formatted {@code XXXX-XXXX}; a credential, never logged. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdmissionCheckDto(
        @JsonProperty("admitted") boolean admitted,
        @JsonProperty("reason") String reason,
        @JsonProperty("code") String code,
        @JsonProperty("expiresAt") Instant expiresAt
    ) {
        @Override
        public String toString() {
            return "AdmissionCheckDto[admitted=" + admitted + ", reason=" + reason + "]";
        }
    }

    /** The {@code accountLinked} payload of an {@code AccountLinked} player notification. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountLinkedNotificationDto(@JsonProperty("maskedEmail") String maskedEmail) {
    }
}
