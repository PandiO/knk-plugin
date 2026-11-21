package net.knightsandkings.knk.core.siege.shop;

import net.knightsandkings.knk.core.siege.economy.SiegePointsEconomy;
import net.knightsandkings.knk.core.siege.economy.SiegePointsSettings;
import net.knightsandkings.knk.core.siege.economy.SiegePointsSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The purchase order of siege shop DESIGN § 5.5 against the real siege points ledger (P22, P23, A18–A22, A26;
 * PandiO/knk-workspace#868): check, then charge, then give; every refusal charges nothing and names its reason.
 */
class ShopPurchaseServiceTest {

    private static final UUID A1 = new UUID(0, 1);
    private static final UUID GHOST = new UUID(0, 9);

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<String> given = new ArrayList<>();
    private final List<ShopPurchaseResult.Bought> told = new ArrayList<>();
    private final Set<UUID> dead = new HashSet<>();
    private final AtomicBoolean room = new AtomicBoolean(true);
    private final AtomicBoolean giveFails = new AtomicBoolean(false);
    private SiegePointsEconomy economy;
    private ShopPurchaseService shop;

    /** The item handler's shape: room check, then charge, then give (reverse on a failed give). */
    private final ShopPurchaseHandler items = new ShopPurchaseHandler() {
        @Override
        public ShopEntryKind kind() {
            return ShopEntryKind.ITEM;
        }

        @Override
        public Optional<ShopRefusal> checkDelivery(UUID buyer, ShopEntry entry) {
            return room.get() ? Optional.empty() : Optional.of(ShopRefusal.inventoryFull());
        }

        @Override
        public ShopPurchaseResult fulfil(ShopPurchaseTicket ticket) {
            ShopPurchaseResult charged = ticket.charge();
            if (!(charged instanceof ShopPurchaseResult.Bought)) return charged;
            if (giveFails.get()) {
                ticket.reverse();
                return new ShopPurchaseResult.Refused(ShopRefusal.deliveryFailed());
            }
            given.add(ticket.entry().name() + " x" + ticket.entry().quantity());
            return charged;
        }
    };

    @BeforeEach
    void setUp() {
        start(ShopFixtures.catalogue());
    }

    private void start(ShopCatalogue catalogue) {
        economy = new SiegePointsEconomy("match-1", SiegePointsSettings.defaults());
        economy.join(A1);
        shop = new ShopPurchaseService("match-1", catalogue, new SiegePointsShopWallet(economy),
                p -> dead.contains(p) ? Optional.of(ShopRefusal.notAlive())
                        : economy.isMember(p) ? Optional.empty() : Optional.of(ShopRefusal.notInMatch()),
                now::get, (p, b) -> told.add(b)).handler(items);
    }

    private void earn(long amount) {
        economy.credit(A1, SiegePointsSource.KILL, amount);
    }

    private ShopRefusal.Reason refused(ShopPurchaseResult result) {
        return assertInstanceOf(ShopPurchaseResult.Refused.class, result).refusal().reason();
    }

    @Test
    void aPurchaseChargesTheCurrentPriceAndGivesTheFullQuantity_A18() {
        earn(400);
        ShopPurchaseResult.Bought bought = assertInstanceOf(ShopPurchaseResult.Bought.class, shop.purchase(A1, 11));
        assertEquals(300, bought.price());
        assertEquals(1, bought.purchaseNumber());
        assertEquals(100, economy.balance(A1));
        assertEquals(300, economy.spent(A1));
        assertEquals("shop:match-1:" + A1 + ":11:1", bought.chargeId());
        assertEquals(List.of("Ender Pearl x1"), given);
        assertEquals(List.of(bought), told, "telemetry hears every charged purchase (P27)");

        earn(40);
        assertInstanceOf(ShopPurchaseResult.Bought.class, shop.purchase(A1, 13));
        assertEquals("Arrow x16", given.get(1));

        ShopPurchaseService.EntryState pearl = shop.state(A1, ShopFixtures.pearl());
        assertEquals(1, pearl.purchases());
        assertEquals(350, pearl.price());
        assertEquals(20_000, pearl.cooldownMillisLeft());
    }

    @Test
    void eachRefusalChargesNothingAndNamesItsReason_A19() {
        earn(100);
        ShopPurchaseResult r = shop.purchase(A1, 11);
        assertEquals(ShopRefusal.Reason.NOT_ENOUGH_POINTS, refused(r));
        assertEquals("Not enough siege points: 100/300.", ((ShopPurchaseResult.Refused) r).refusal().message());

        earn(1000);
        room.set(false);
        assertEquals(ShopRefusal.Reason.INVENTORY_FULL, refused(shop.purchase(A1, 11)));
        room.set(true);

        assertInstanceOf(ShopPurchaseResult.Bought.class, shop.purchase(A1, 11));
        long afterOne = economy.balance(A1);
        now.addAndGet(8_000);
        ShopPurchaseResult cooling = shop.purchase(A1, 11);
        assertEquals(ShopRefusal.Reason.COOLDOWN, refused(cooling));
        assertEquals("Cooldown: 12 s.", ((ShopPurchaseResult.Refused) cooling).refusal().message());

        assertEquals(ShopRefusal.Reason.UNKNOWN_ENTRY, refused(shop.purchase(A1, 999)));
        assertEquals(ShopRefusal.Reason.NOT_IN_MATCH, refused(shop.purchase(GHOST, 11)));
        dead.add(A1);
        assertEquals(ShopRefusal.Reason.NOT_ALIVE, refused(shop.purchase(A1, 13)));
        dead.clear();

        assertEquals(afterOne, economy.balance(A1), "no refusal charged anything");
        assertEquals(List.of("Ender Pearl x1"), given);
        assertEquals(1, told.size());
    }

    @Test
    void pricesAndCooldownsGrowUntilTheCap_A20_A21_A22() {
        earn(100_000);
        List<Long> prices = new ArrayList<>();
        List<Long> cooldowns = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ShopPurchaseResult.Bought b = assertInstanceOf(ShopPurchaseResult.Bought.class, shop.purchase(A1, 11));
            prices.add(b.price());
            long left = shop.state(A1, ShopFixtures.pearl()).cooldownMillisLeft();
            cooldowns.add(left);
            now.addAndGet(left);
        }
        assertEquals(List.of(300L, 350L, 450L, 600L, 800L, 1050L, 1350L, 1700L), prices);
        assertEquals(List.of(20_000L, 30_000L, 45_000L, 67_500L), cooldowns.subList(0, 4));
        ShopPurchaseResult ninth = shop.purchase(A1, 11);
        assertEquals(ShopRefusal.Reason.LIMIT_REACHED, refused(ninth));
        assertEquals("Limit reached: 8 per match.", ((ShopPurchaseResult.Refused) ninth).refusal().message());
        assertTrue(shop.state(A1, ShopFixtures.pearl()).capReached());

        // A22: the next match starts over (counters live with the match's shop).
        start(ShopFixtures.catalogue());
        earn(300);
        ShopPurchaseService.EntryState fresh = shop.state(A1, ShopFixtures.pearl());
        assertEquals(0, fresh.purchases());
        assertEquals(300, fresh.price());
        assertEquals(0, fresh.cooldownMillisLeft());
        assertInstanceOf(ShopPurchaseResult.Bought.class, shop.purchase(A1, 11));
    }

    @Test
    void theCatalogueIsTheOneTheMatchStartedWith_A23() {
        earn(1000);
        ShopCatalogue edited = new ShopCatalogue("Siege", 4, List.of(new ShopCategory(2, "consumables", "Consumables",
                null, 0, List.of(ShopFixtures.withPrice(ShopFixtures.pearl(), 999, null, ShopCurve.NONE, null, null)))));
        ShopPurchaseService running = shop;
        start(edited); // a later match reads the edited catalogue
        assertEquals(999, shop.state(A1, shop.catalogue().entry(11).orElseThrow()).price());
        assertEquals(300, running.state(A1, running.catalogue().entry(11).orElseThrow()).price(),
                "the running match keeps the price it started with");
    }

    @Test
    void aLockedWeaponIsRefusedAndShownLocked_andWeaponsWaitForTheirHandler_A26() {
        earn(1000);
        shop.unlocks((p, e) -> false);
        ShopPurchaseResult locked = shop.purchase(A1, 21);
        assertEquals(ShopRefusal.Reason.LOCKED, refused(locked));
        assertTrue(((ShopPurchaseResult.Refused) locked).refusal().message().contains("/menu → Siege Minigame → Siege unlocks"));
        assertEquals(ShopRefusal.Reason.LOCKED, shop.blocked(A1, ShopFixtures.trebuchet()).orElseThrow().reason());

        shop.unlocks(ShopPurchaseService.Unlocks.ALL);
        assertEquals(ShopRefusal.Reason.NOT_AVAILABLE, refused(shop.purchase(A1, 21)), "no weapon handler before #784");
        assertTrue(shop.blocked(A1, ShopFixtures.pearl()).isEmpty());
        assertEquals(1000, economy.balance(A1));
    }

    @Test
    void aWeaponHandlerChargesLaterThroughItsTicket_andOnlyOnce_W67() {
        earn(500);
        List<ShopPurchaseTicket> pending = new ArrayList<>();
        shop.handler(new ShopPurchaseHandler() {
            @Override
            public ShopEntryKind kind() {
                return ShopEntryKind.SIEGE_WEAPON;
            }

            @Override
            public ShopPurchaseResult fulfil(ShopPurchaseTicket ticket) {
                pending.add(ticket); // the spot picker opens; nothing charged yet
                return new ShopPurchaseResult.Started(ticket.entry());
            }
        });
        assertInstanceOf(ShopPurchaseResult.Started.class, shop.purchase(A1, 21));
        assertEquals(500, economy.balance(A1), "nothing charged before the spot is checked");

        ShopPurchaseTicket ticket = pending.get(0);
        assertEquals(200, ticket.price());
        ShopPurchaseResult.Bought first = assertInstanceOf(ShopPurchaseResult.Bought.class, ticket.charge());
        assertEquals(first, ticket.charge(), "a second charge answers the first");
        assertEquals(300, economy.balance(A1));

        // A ticket whose buyer spent the points meanwhile is refused at its charge.
        assertInstanceOf(ShopPurchaseResult.Started.class, shop.purchase(A1, 21));
        assertInstanceOf(ShopPurchaseResult.Bought.class, shop.purchase(A1, 11));
        assertEquals(ShopRefusal.Reason.NOT_ENOUGH_POINTS, refused(pending.get(1).charge()));
        assertEquals(0, economy.balance(A1));
    }

    @Test
    void aFailedDeliveryGivesThePointsBack_andTheNextChargeUsesAFreshRetryId() {
        earn(400);
        giveFails.set(true);
        assertEquals(ShopRefusal.Reason.DELIVERY_FAILED, refused(shop.purchase(A1, 11)));
        assertEquals(400, economy.balance(A1));
        assertEquals(0, economy.spent(A1));
        assertEquals(0, shop.state(A1, ShopFixtures.pearl()).purchases(), "the reversed purchase does not count");
        assertEquals(0, shop.state(A1, ShopFixtures.pearl()).cooldownMillisLeft());

        giveFails.set(false);
        ShopPurchaseResult.Bought again = assertInstanceOf(ShopPurchaseResult.Bought.class, shop.purchase(A1, 11));
        assertEquals(100, economy.balance(A1), "charged for real, not answered as a replay");
        assertEquals("shop:match-1:" + A1 + ":11:1:again1", again.chargeId());
    }

    @Test
    void nothingIsSoldAfterTheMatchEnded_A24() {
        earn(400);
        shop.end();
        economy.end();
        assertEquals(ShopRefusal.Reason.NOT_IN_MATCH, refused(shop.purchase(A1, 11)));
        assertFalse(shop.ended() && given.size() > 0);
    }
}
