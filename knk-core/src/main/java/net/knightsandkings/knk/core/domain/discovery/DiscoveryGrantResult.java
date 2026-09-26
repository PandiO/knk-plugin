package net.knightsandkings.knk.core.domain.discovery;

import java.util.List;

import net.knightsandkings.knk.core.domain.users.RewardMultiplier;
import net.knightsandkings.knk.core.domain.users.TitleChangeResult;

/**
 * Result of {@code POST api/users/{userId}/discoveries} (knk-web-api's DiscoveryGrantResultDto).
 * Idempotent on the server: a repeat grants nothing and lists the domains under
 * {@link #alreadyDiscovered()}.
 *
 * @param granted           newly discovered domains, top-down (Town, District, Structure) - the
 *                          order to show them in
 * @param alreadyDiscovered domain ids (requested or ancestors) the player had already found
 * @param coinMultipliers   applied to every granted domain's coins: personal first, then one per
 *                          rank (KNG-16); likewise gems and XP with their own multipliers
 * @param titleChange       set when the discovery XP crossed a title bracket (not queued for the
 *                          notification poller - the plugin shows it from this result)
 */
public record DiscoveryGrantResult(
    List<DiscoveryGrant> granted,
    List<Integer> alreadyDiscovered,
    List<DiscoverySkip> skipped,
    int totalCoins,
    int totalGems,
    int totalExp,
    int totalCoinsBase,
    int totalGemsBase,
    int totalExpBase,
    List<RewardMultiplier> coinMultipliers,
    List<RewardMultiplier> gemMultipliers,
    List<RewardMultiplier> expMultipliers,
    Integer titleBracketId,
    int newCoins,
    int newGems,
    int newExperiencePoints,
    TitleChangeResult titleChange
) {
    public DiscoveryGrantResult {
        granted = granted == null ? List.of() : List.copyOf(granted);
        alreadyDiscovered = alreadyDiscovered == null ? List.of() : List.copyOf(alreadyDiscovered);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
        coinMultipliers = coinMultipliers == null ? List.of() : List.copyOf(coinMultipliers);
        gemMultipliers = gemMultipliers == null ? List.of() : List.copyOf(gemMultipliers);
        expMultipliers = expMultipliers == null ? List.of() : List.copyOf(expMultipliers);
    }

    public boolean hasGrants() {
        return !granted.isEmpty();
    }
}
