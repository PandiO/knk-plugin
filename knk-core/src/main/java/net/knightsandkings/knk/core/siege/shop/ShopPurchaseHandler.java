package net.knightsandkings.knk.core.siege.shop;

import java.util.Optional;
import java.util.UUID;

/**
 * The port per entry kind (siege shop DESIGN § 7.1.4, P19, P22; PandiO/knk-workspace#868). {@link ShopPurchaseService}
 * runs the shared checks in the § 5.5 order (the weapon unlock
 * included, {@link ShopPurchaseService.Unlocks}) and asks the handler only for what depends on the kind:
 * <ol>
 *   <li>{@link #checkDelivery}: after the cooldown, before the balance (room in the inventory for the whole quantity
 *       for items; the weapon flow's own checks for weapons, W67).</li>
 *   <li>{@link #fulfil}: every check passed. An item handler charges at once through the ticket and gives the items;
 *       a siege weapon handler (#784's {@code SiegeWeaponPurchaseHandler}) opens its spot picker and returns
 *       {@link ShopPurchaseResult.Started}, then charges through the same ticket after its own placement checks, and
 *       never before them (W67).</li>
 * </ol>
 * Main thread only. Implementations: {@code ItemPurchaseHandler} (knk-paper, this link); siege weapons phase 3 (#784)
 * registers the weapon one with {@code SiegeShopService.registerHandler}. Until then weapon entries are refused
 * ({@link ShopRefusal.Reason#NOT_AVAILABLE}).
 */
public interface ShopPurchaseHandler {

    /** The kind this handler sells. */
    ShopEntryKind kind();

    /** Whether the purchase could be delivered now (inventory room for items); empty when it can. */
    default Optional<ShopRefusal> checkDelivery(UUID buyer, ShopEntry entry) {
        return Optional.empty();
    }

    /**
     * Every check passed: charge through {@link ShopPurchaseTicket#charge()} and deliver, calling
     * {@link ShopPurchaseTicket#reverse()} when the delivery fails after the charge; or start an own flow and return
     * {@link ShopPurchaseResult.Started}.
     */
    ShopPurchaseResult fulfil(ShopPurchaseTicket ticket);
}
