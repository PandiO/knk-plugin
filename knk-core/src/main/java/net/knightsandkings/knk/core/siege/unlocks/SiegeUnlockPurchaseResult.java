package net.knightsandkings.knk.core.siege.unlocks;

/**
 * The answer of {@code POST api/SiegeWeaponUnlocks/purchase} (PandiO/knk-workspace#869): unlocked (now, or already
 * before: nothing charged), or refused with the API's reason (title level, coins; nothing charged).
 */
public sealed interface SiegeUnlockPurchaseResult {

    record Unlocked(String templateName, long coinsPaid, boolean alreadyUnlocked, long coins) implements SiegeUnlockPurchaseResult {
    }

    record Refused(String code, String message) implements SiegeUnlockPurchaseResult {
    }
}
