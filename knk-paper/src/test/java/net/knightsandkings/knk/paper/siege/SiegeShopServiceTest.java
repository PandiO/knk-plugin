package net.knightsandkings.knk.paper.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeConfiguration;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeMatchLength;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeObjective;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeTeam;
import net.knightsandkings.knk.core.domain.siege.SiegeGateState;
import net.knightsandkings.knk.core.domain.siege.SiegeTeamRole;
import net.knightsandkings.knk.core.ports.api.SiegeShopApi;
import net.knightsandkings.knk.core.siege.economy.SiegePointsEconomy;
import net.knightsandkings.knk.core.siege.economy.SiegePointsSettings;
import net.knightsandkings.knk.core.siege.economy.SiegePointsSource;
import net.knightsandkings.knk.core.siege.shop.ShopCatalogue;
import net.knightsandkings.knk.core.siege.shop.ShopCategory;
import net.knightsandkings.knk.core.siege.shop.ShopCurve;
import net.knightsandkings.knk.core.siege.shop.ShopEntry;
import net.knightsandkings.knk.core.siege.shop.ShopEntryKind;
import net.knightsandkings.knk.core.siege.shop.ShopEntryView;
import net.knightsandkings.knk.core.siege.shop.ShopPurchaseResult;
import net.knightsandkings.knk.core.siege.shop.ShopPurchaseService;
import net.knightsandkings.knk.core.siege.shop.SiegePointsShopWallet;
import net.knightsandkings.knk.paper.siege.points.SiegePointsService;
import net.knightsandkings.knk.paper.siege.shop.SiegeShopService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The siege shop in a PvP match on a MockBukkit server (siege shop DESIGN § 4.1, § 5.3–5.5, P02, P22–P25;
 * PandiO/knk-workspace#868): the catalogue read once per match, item purchases into a real inventory with the
 * check-charge-give order, the match end, the weapon lock and the Survival hook. A17–A24, A26.
 */
class SiegeShopServiceTest {

    private static final int DEFENDERS = 1;
    private static final int ATTACKERS = 2;

    private ServerMock server;
    private PlayerMock alice;
    private PlayerMock dave;
    private SiegeMatch match;
    private final SiegeLobbyRuntime lobby = mock(SiegeLobbyRuntime.class);
    private SiegePointsService points;
    private SiegeShopService shop;
    private final AtomicReference<ShopCatalogue> apiCatalogue = new AtomicReference<>();
    private final AtomicInteger catalogueReads = new AtomicInteger();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private final Map<Integer, Set<Integer>> unlocks = new HashMap<>();
    private final List<Runnable> later = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<UUID> opened = new ArrayList<>();
    private int matchNo;

    private static ShopEntry item(int id, int blueprint, String name, int quantity, long price, Long step, Double cooldown, Integer cap) {
        return new ShopEntry(id, 2, ShopEntryKind.ITEM, "Default", blueprint, name, quantity, null, null, null, null,
                price, step, step == null ? ShopCurve.NONE : ShopCurve.LINEAR, null, null, cooldown, null, ShopCurve.NONE,
                null, null, cap, id);
    }

    private static ShopCatalogue catalogue(long pearlPrice) {
        ShopEntry trebuchet = new ShopEntry(21, 1, ShopEntryKind.SIEGE_WEAPON, "Default", null, null, 1, 5, "Trebuchet",
                null, null, 200, null, ShopCurve.NONE, null, null, null, null, ShopCurve.NONE, null, null, null, 0);
        return new ShopCatalogue("Siege", 1, List.of(
                new ShopCategory(1, "siege-weapons", "Siege weapons", null, 0, List.of(trebuchet)),
                new ShopCategory(2, "consumables", "Consumables", null, 1, List.of(
                        item(11, 101, "Ender Pearl", 1, pearlPrice, 50L, 20.0, 8),
                        item(12, 102, "Splash Potion of Healing II", 1, 150, 25L, 10.0, 10),
                        item(13, 103, "Arrow", 16, 40, null, null, null))),
                new ShopCategory(3, "magic", "Magic", null, 2, List.of())));
    }

    private final SiegeShopApi api = new SiegeShopApi() {
        @Override
        public CompletableFuture<ShopCatalogue> catalogue(String gameMode, Integer scenarioId) {
            catalogueReads.incrementAndGet();
            if (failuresLeft.getAndDecrement() > 0) return CompletableFuture.failedFuture(new RuntimeException("API down"));
            return CompletableFuture.completedFuture(apiCatalogue.get());
        }

        @Override
        public CompletableFuture<Set<Integer>> unlockedWeaponTemplates(int userId) {
            return CompletableFuture.completedFuture(unlocks.getOrDefault(userId, Set.of()));
        }
    };

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        alice = server.addPlayer("Alice");
        dave = server.addPlayer("Dave");
        apiCatalogue.set(catalogue(300));
        when(lobby.id()).thenReturn(7);
        when(lobby.displayName()).thenReturn("Siege");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void start(boolean weapons) {
        points = new SiegePointsService(Logger.getLogger("test"), this::matchOf, null, null);
        Map<UUID, Integer> userIds = Map.of(alice.getUniqueId(), 31, dave.getUniqueId(), 32);
        shop = new SiegeShopService(Logger.getLogger("test"), api, this::matchOf, points::economyOf, userIds::get,
                Bukkit::getPlayer, id -> CompletableFuture.completedFuture(new ItemStack(switch (id) {
                    case 101 -> Material.ENDER_PEARL;
                    case 102 -> Material.SPLASH_POTION;
                    default -> Material.ARROW;
                })), weapons, null, Runnable::run, (ticks, task) -> later.add(task), now::get);
        shop.setMenus(p -> opened.add(p.getUniqueId()));
        newMatch();
    }

    private void newMatch() {
        KnkSiegeTeam defenders = new KnkSiegeTeam(DEFENDERS, 0, SiegeTeamRole.DEFENDER, 1, null, "Defenders", "BLUE",
                null, null, List.of());
        KnkSiegeTeam attackers = new KnkSiegeTeam(ATTACKERS, 1, SiegeTeamRole.ATTACKER, 2, null, "Attackers", "RED",
                null, null, List.of());
        KnkSiegeObjective keep = new KnkSiegeObjective(2, 2, "Keep", null, null, 100, 2.5, false, DEFENDERS, true,
                SiegeGateState.OPEN);
        KnkSiegeScenario scenario = new KnkSiegeScenario(1, "Scenario", null, 1, "Town", null, List.of(), null,
                2, 20, null, null, KnkSiegeMatchLength.DEFAULT, null, true, true, true,
                List.of(defenders, attackers), List.of(keep), List.of(), List.of());
        KnkSiegeConfiguration config = KnkSiegeConfiguration.legacyDefaults().withPoints(SiegePointsSettings.defaults());
        match = new SiegeMatch(scenario, config, "token-" + (++matchNo),
                Map.of(DEFENDERS, List.of(dave.getUniqueId()), ATTACKERS, List.of(alice.getUniqueId())));
        when(lobby.match()).thenReturn(Optional.of(match));
        points.matchStarted(lobby, match);
        shop.matchStarted(lobby, match);
    }

    private void endMatch() {
        points.matchEnded(lobby, match);
        shop.matchEnded(lobby, match);
        points.roundReleased(lobby);
        shop.roundReleased(lobby);
    }

    private Optional<SiegeMatch> matchOf(UUID id) {
        return match != null && match.roster().contains(id) ? Optional.of(match) : Optional.empty();
    }

    private SiegePointsEconomy economy() {
        return points.economyOf(match).orElseThrow();
    }

    private void earn(Player p, long amount) {
        economy().credit(p.getUniqueId(), SiegePointsSource.KILL, amount);
    }

    private static String plain(Component c) {
        if (c == null) return null;
        String text = PlainTextComponentSerializer.plainText().serialize(c);
        return text.startsWith("[Siege] ") ? text.substring("[Siege] ".length()) : text;
    }

    private int count(Player p, Material m) {
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) if (s != null && s.getType() == m) n += s.getAmount();
        return n;
    }

    @Test
    void theShopOpensAnywhereDuringTheMatch_withItsCategoriesAndNoWeaponsWhileTheyAreOff_A17() {
        start(false);
        assertNull(plain(shop.open(alice)), "opened");
        assertEquals(List.of(alice.getUniqueId()), opened);
        assertTrue(shop.available(alice.getUniqueId()));
        assertEquals(List.of("&aConsumables"), shop.categories(alice).stream().map(c -> c.getName()).toList(),
                "no weapons (siege.weapons.enabled off) and no empty Magic");
        earn(alice, 650);
        assertEquals("&7Siege points: &6650", shop.header(alice, 2).getBalanceLine());
        assertEquals("&aConsumables", shop.header(alice, 2).getCategoryName());
        assertEquals("ENDER_PEARL", shop.entries(alice, 2).get(0).getMaterial());

        PlayerMock outsider = server.addPlayer("Olga");
        assertEquals("The siege shop is open only during a running siege match.", plain(shop.open(outsider)));
        assertFalse(shop.available(outsider.getUniqueId()));
    }

    @Test
    void aPurchaseChargesTheCurrentPriceAndGivesTheFullQuantity_A18_A20_A21() {
        start(false);
        earn(alice, 400);
        assertEquals("Bought Ender Pearl for 300 siege points. Balance: 100.", plain(shop.buy(alice, 11)));
        assertEquals(1, count(alice, Material.ENDER_PEARL));
        assertEquals(300, economy().spent(alice.getUniqueId()));

        ShopEntryView pearl = shop.entries(alice, 2).get(0);
        assertTrue(pearl.getLines().contains("&7Price: &6350 siege points"));
        assertTrue(pearl.getLines().contains("&7Bought: &f1/8"));
        assertTrue(pearl.getLines().contains("&cCooldown: 20 s"));

        earn(alice, 40);
        assertEquals("Bought Arrow ×16 for 40 siege points. Balance: 100.", plain(shop.buy(alice, 13)));
        assertEquals(16, count(alice, Material.ARROW));
    }

    @Test
    void eachRefusalChargesNothing_andAFullInventoryIsRefused_A19() {
        start(false);
        earn(alice, 100);
        assertEquals("Not enough siege points: 100/300.", plain(shop.buy(alice, 11)));
        earn(alice, 900);
        for (int slot = 0; slot < 36; slot++) alice.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        assertEquals("Inventory full: make room for the whole stack first.", plain(shop.buy(alice, 12)));
        alice.getInventory().setItem(0, null);
        assertTrue(plain(shop.buy(alice, 11)).startsWith("Bought"));
        assertEquals("Cooldown: 20 s.", plain(shop.buy(alice, 11)));
        assertEquals(700, economy().balance(alice.getUniqueId()), "only the one purchase was charged");
        assertEquals(1, count(alice, Material.ENDER_PEARL));
    }

    @Test
    void theCatalogueIsReadOnceAtTheStart_editsApplyFromTheNextMatch_andCountersStartOver_A22_A23() {
        start(false);
        earn(alice, 1000);
        assertTrue(plain(shop.buy(alice, 11)).startsWith("Bought"));
        apiCatalogue.set(catalogue(999)); // the admin edits the pearl during the match
        now.addAndGet(60_000);
        assertEquals("Bought Ender Pearl for 350 siege points. Balance: 350.", plain(shop.buy(alice, 11)));
        assertEquals(1, catalogueReads.get());

        endMatch();
        newMatch();
        assertEquals(2, catalogueReads.get());
        earn(alice, 1000);
        ShopEntryView fresh = shop.entries(alice, 2).get(0);
        assertTrue(fresh.getLines().contains("&7Price: &6999 siege points"), "the next match uses the edit: " + fresh.getLines());
        assertTrue(fresh.getLines().contains("&7Bought: &f0/8"), "counters start over");
    }

    @Test
    void afterTheMatchNothingIsSold_andTheSpentPointsAreInTheResult_A11_A24() {
        start(false);
        earn(alice, 400);
        shop.buy(alice, 11);
        points.matchEnded(lobby, match);
        shop.matchEnded(lobby, match);
        assertEquals("The siege shop is open only during a running siege match.", plain(shop.buy(alice, 13)));
        assertEquals(100, economy().balance(alice.getUniqueId()), "no refund at the end (P23)");
        assertEquals(300, economy().spent(alice.getUniqueId()), "spent goes into the match result (L1's withTotals)");
    }

    @Test
    void aDeadMemberCantBuy() {
        start(false);
        earn(alice, 400);
        alice.setHealth(0);
        assertEquals("You can buy only while you are alive in the match.", plain(shop.buy(alice, 11)));
        assertEquals(400, economy().balance(alice.getUniqueId()));
    }

    @Test
    void aLockedWeaponShowsWhereToUnlockIt_andCantBeBought_A26() {
        start(true);
        earn(alice, 1000);
        assertEquals(List.of("&aSiege weapons", "&aConsumables"), shop.categories(alice).stream().map(c -> c.getName()).toList());
        ShopEntryView trebuchet = shop.entries(alice, 1).get(0);
        assertEquals("DISABLED", trebuchet.getDisplayMode());
        assertTrue(trebuchet.getLines().contains("&cLocked: unlock it in /menu → Minigames → Siege unlocks."));
        assertEquals("Locked: unlock it in /menu → Minigames → Siege unlocks.", plain(shop.buy(alice, 21)));

        unlocks.put(31, Set.of(5));
        endMatch();
        newMatch();
        earn(alice, 1000);
        assertEquals("Siege weapons can't be bought yet.", plain(shop.buy(alice, 21)), "unlocked, but no handler before #784");
        assertEquals(1000, economy().balance(alice.getUniqueId()));
    }

    @Test
    void aFailedCatalogueReadIsRetriedTwice_thenTheMatchHasNoShop() {
        failuresLeft.set(5);
        start(false);
        assertEquals("The siege shop is still opening; try again in a moment.", plain(shop.open(alice)));
        assertTrue(shop.available(alice.getUniqueId()), "the Shop button shows while it opens");
        while (!later.isEmpty()) later.remove(0).run();
        assertEquals(3, catalogueReads.get());
        assertEquals("The siege shop isn't available in this match.", plain(shop.open(alice)));
        assertFalse(shop.available(alice.getUniqueId()));

        failuresLeft.set(1);
        endMatch();
        newMatch();
        while (!later.isEmpty()) later.remove(0).run();
        assertNull(plain(shop.open(alice)), "a retry that works opens the shop");
    }

    @Test
    void anotherModesShopIsUsedThroughTheSurvivalHook_P21() {
        start(false);
        PlayerMock survivor = server.addPlayer("Sam");
        SiegePointsEconomy survival = new SiegePointsEconomy("survival-1", SiegePointsSettings.defaults());
        survival.join(survivor.getUniqueId());
        survival.credit(survivor.getUniqueId(), SiegePointsSource.KILL, 500);
        ShopPurchaseService survivalShop = new ShopPurchaseService("survival-1", catalogue(300).withoutWeapons(),
                new SiegePointsShopWallet(survival), p -> Optional.empty(), now::get, null)
                .handler(new net.knightsandkings.knk.paper.siege.shop.ItemPurchaseHandler(Bukkit::getPlayer,
                        Map.of(11, new ItemStack(Material.ENDER_PEARL))));
        shop.addMatchSource(id -> id.equals(survivor.getUniqueId()) ? Optional.of(survivalShop) : Optional.empty());

        assertNull(plain(shop.open(survivor)));
        assertEquals("Bought Ender Pearl for 300 siege points. Balance: 200.", plain(shop.buy(survivor, 11)));
        assertEquals(200, survival.balance(survivor.getUniqueId()), "the same balance Survival's purchase points charge (A25)");
    }

    @Test
    void aStartedWeaponPurchaseSaysNothingItself() {
        assertNull(plain(SiegeShopService.message(new ShopPurchaseResult.Started(catalogue(300).entry(21).orElseThrow()))));
    }
}
