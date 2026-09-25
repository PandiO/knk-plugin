package net.knightsandkings.knk.core.domain.users;

/**
 * Result of UsersCommandApi.adjustBalancesById - the new balances plus, if the XP delta crossed
 * one or more title brackets, everything needed to show one consolidated promotion/demotion
 * notification (see TitleChangeResult).
 */
public record BalanceAdjustmentResult(
    int newCoins,
    int newGems,
    int newExperiencePoints,
    TitleChangeResult titleChange // null if no bracket was crossed
) {}
