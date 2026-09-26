package net.knightsandkings.knk.core.domain.siege;

/**
 * A team's role (docs/specs/siege-minigame/DESIGN.md §3.4). It drives defaults only: the initial
 * objective holder, the timeout tie-break and UI wording. Scoring is holder-relative (§7.1), so no
 * capture or win rule compares against a role or a team name.
 */
public enum SiegeTeamRole {
    DEFENDER,
    ATTACKER;

    /**
     * Parses the API's string enum ("Defender"/"Attacker", any case). Unknown or missing values
     * fall back to {@link #ATTACKER}: a role only picks defaults, and the server resolves those
     * before sending the runtime config.
     */
    public static SiegeTeamRole fromApi(String value) {
        return SiegeEnums.parse(SiegeTeamRole.class, value, ATTACKER);
    }
}
