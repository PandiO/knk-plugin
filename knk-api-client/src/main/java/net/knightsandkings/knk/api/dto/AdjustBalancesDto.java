package net.knightsandkings.knk.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of PUT /api/Users/{id}/balances (AdjustBalancesDto in knk-web-api, currency ledger
 * KNG-21 Phase 2): staff changes as {currency, mode, amount}; the server applies them.
 */
public record AdjustBalancesDto(
    @JsonProperty("changes") List<Change> changes,
    @JsonProperty("reason") String reason,
    // false = the caller shows any resulting title change in-game itself, so the API must not
    // also queue it for PlayerNotificationPoller (which would show it a second time)
    @JsonProperty("notifyPlayer") boolean notifyPlayer
) {
    /** currency "Coins"|"Gems"|"Experience", mode "Add"|"Remove"|"Set". */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Change(
        @JsonProperty("currency") String currency,
        @JsonProperty("mode") String mode,
        @JsonProperty("amount") long amount,
        @JsonProperty("expectedCurrent") Long expectedCurrent
    ) {}
}
