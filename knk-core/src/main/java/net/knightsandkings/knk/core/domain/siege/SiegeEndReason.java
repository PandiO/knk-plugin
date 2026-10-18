package net.knightsandkings.knk.core.domain.siege;

/**
 * Why a match ended (DESIGN §3.10 {@code SiegeMatch.EndReason}, §7.5). The first four end with
 * normal win resolution; the last two abort the match with no winner and no rewards.
 */
public enum SiegeEndReason {
    INSTANT_VICTORY,
    TIME_EXPIRED,
    TEAM_ELIMINATED,
    NOT_ENOUGH_PLAYERS,
    ADMIN_STOPPED,
    SERVER_RESTART,
    /** An API-outage action evacuated a participant (hub DESIGN §16.4 decision 6). */
    API_OUTAGE;

    /** True for admin stop, server restart and an API outage: aborted, no winner, no rewards. */
    public boolean isAbort() {
        return this == ADMIN_STOPPED || this == SERVER_RESTART || this == API_OUTAGE;
    }

    /** The API's PascalCase name ({@code SiegeMatchEndReason}), e.g. {@code InstantVictory}. */
    public String apiName() {
        return SiegeEnums.toApiName(this);
    }
}
