package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlockPurchaseResult;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlocksOverview;

import java.util.concurrent.CompletableFuture;

/**
 * Siege weapon unlocks with coins (siege shop DESIGN § 6.2.3-6.2.4, W32; PandiO/knk-workspace#869), with the plugin key.
 */
public interface SiegeWeaponUnlocksApi {

    /** {@code GET api/SiegeWeaponUnlocks/by-user/{userId}}: every template with the player's state, coins and title. */
    CompletableFuture<SiegeUnlocksOverview> overview(int userId);

    /**
     * {@code POST api/SiegeWeaponUnlocks/purchase}: checks the title level and the coins, then charges once per player and
     * template. A refusal (409) completes normally as {@link SiegeUnlockPurchaseResult.Refused}; other failures fail the
     * future.
     */
    CompletableFuture<SiegeUnlockPurchaseResult> purchase(int userId, int templateId);
}
