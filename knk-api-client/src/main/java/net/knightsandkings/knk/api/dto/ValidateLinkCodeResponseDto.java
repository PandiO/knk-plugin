package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO for link code validation.
 * Returned from /api/Users/validate-link-code endpoint: whether the code is valid and, if so, the
 * username of the account it belongs to. The API sends no user id or email (closed-alpha hardening);
 * an older API that still sends them is fine, the client ignores unknown fields.
 */
public record ValidateLinkCodeResponseDto(
    @JsonProperty("isValid")
    Boolean isValid,
    
    @JsonProperty("username")
    String username,
    
    @JsonProperty("error")
    String error
) {
}
