package net.knightsandkings.knk.core.domain.users;

import java.util.List;

/**
 * A consolidated title-bracket change from one AdjustBalancesById call - every bracket crossed
 * is summed into ONE result, never fired once per tier (v1's TitleChangeEvents looped once per
 * tier on a timer; this project explicitly does not repeat that). See
 * knk-web-api's TitleChangeResultDto, which this mirrors.
 */
public record TitleChangeResult(
    String direction, // "promotion" or "demotion"
    int fromTitleBracketId,
    String fromTitleName,
    int toTitleBracketId,
    String toTitleName,
    List<TitleCrossing> crossedTitles, // promotion only, per-tier bonuses folded into the totals below
    int coinBonusGranted,
    int gemBonusGranted,
    int expBonusGranted,
    // KNG-16: the bonuses summed before multipliers, and the multipliers applied to each
    // (personal, then one per rank). 0/empty on demotion or from an API that predates them.
    int coinBonusBase,
    int gemBonusBase,
    int expBonusBase,
    List<RewardMultiplier> coinBonusMultipliers,
    List<RewardMultiplier> gemBonusMultipliers,
    List<RewardMultiplier> expBonusMultipliers
) {
    public TitleChangeResult {
        coinBonusMultipliers = coinBonusMultipliers == null ? List.of() : List.copyOf(coinBonusMultipliers);
        gemBonusMultipliers = gemBonusMultipliers == null ? List.of() : List.copyOf(gemBonusMultipliers);
        expBonusMultipliers = expBonusMultipliers == null ? List.of() : List.copyOf(expBonusMultipliers);
    }

    public boolean isPromotion() {
        return "promotion".equals(direction);
    }
}
