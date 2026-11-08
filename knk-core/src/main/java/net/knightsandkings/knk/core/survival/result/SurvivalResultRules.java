package net.knightsandkings.knk.core.survival.result;

import net.knightsandkings.knk.core.survival.end.SurvivalEndReason;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The settlement arithmetic the API recomputes for a survival result (DESIGN §9 v1, S32, S62; Siege Survival link
 * S8b, PandiO/knk-workspace#41). Bukkit-free and API-free, so the plugin's numbers can be checked against the API's
 * rules in a unit test: a result settles only for a team wipe, an insta-lose capture or all objectives captured; a
 * player who is still in the match at the end gets the floor of the exact accrual, once; a leaver gets nothing.
 */
public final class SurvivalResultRules {

    /** The API's idempotency key for the match's settlement (one ledger posting per match). */
    public static final String LEDGER_KEY_PREFIX = "siege-survival:";

    private SurvivalResultRules() {
    }

    /** The end reason's wire name in the result contract ({@code TeamWipe}, {@code AdminAbort}, …). */
    public static String endReasonCode(SurvivalEndReason reason) {
        return switch (reason) {
            case TEAM_WIPE -> "TeamWipe";
            case INSTA_LOSE_OBJECTIVE_CAPTURED -> "InstaLoseObjectiveCaptured";
            case ALL_OBJECTIVES_CAPTURED -> "AllObjectivesCaptured";
            case NO_PLAYERS_LEFT -> "NoPlayersLeft";
            case ADMIN_ABORT -> "AdminAbort";
            case SERVER_RESTART -> "ServerRestart";
            case TECHNICAL_LIMIT -> "TechnicalLimit";
        };
    }

    /** The reason behind a wire name, or null when it is not one of the contract's values. */
    public static SurvivalEndReason endReasonOf(String code) {
        if (code == null) return null;
        for (SurvivalEndReason reason : SurvivalEndReason.values()) {
            if (endReasonCode(reason).equals(code)) return reason;
        }
        return null;
    }

    /** True when the reason settles the per-kill permanent rewards (the API's rule and {@link SurvivalEndReason#settlesRewards}). */
    public static boolean settlesRewards(SurvivalEndReason reason) {
        return reason != null && reason.settlesRewards();
    }

    /** {@code siege-survival:{matchId}} when the reason settles, else null (the API stores no key for an abort). */
    public static String ledgerKey(long matchId, SurvivalEndReason reason) {
        return settlesRewards(reason) ? LEDGER_KEY_PREFIX + matchId : null;
    }

    /**
     * What one player is paid: the exact accrual rounded down once (3.3 + 3.3 + 3.3 = 9.9 → 9), only when the reason
     * settles and the player was still in the match at the end; a leaver or an abort pays 0.
     */
    public static long settled(BigDecimal accrued, SurvivalEndReason reason, boolean presentAtEnd) {
        if (!settlesRewards(reason) || !presentAtEnd || accrued == null || accrued.signum() <= 0) return 0L;
        return accrued.setScale(0, RoundingMode.FLOOR).longValueExact();
    }
}
