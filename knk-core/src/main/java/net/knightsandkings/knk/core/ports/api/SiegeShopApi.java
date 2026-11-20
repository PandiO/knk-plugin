package net.knightsandkings.knk.core.ports.api;

import net.knightsandkings.knk.core.siege.shop.ShopCatalogue;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Siege shop reads (siege shop DESIGN § 5.3, § 6.2, P20, P25; PandiO/knk-workspace#868), both with the plugin key:
 * the effective catalogue of a match, read once at its start, and a player's unlocked weapon templates.
 */
public interface SiegeShopApi {

    /**
     * {@code GET api/SiegeShop/catalogue?mode=&scenarioId=}: the mode's enabled default entries with the scenario's
     * overrides applied and its own entries added; prices resolved.
     *
     * @param gameMode   {@code Siege} or {@code SiegeSurvival}
     * @param scenarioId null for the mode's defaults only
     */
    CompletableFuture<ShopCatalogue> catalogue(String gameMode, Integer scenarioId);

    /** {@code GET api/UserSiegeWeaponUnlocks/by-user/{userId}}: the template ids the player has unlocked (W32). */
    CompletableFuture<Set<Integer>> unlockedWeaponTemplates(int userId);
}
