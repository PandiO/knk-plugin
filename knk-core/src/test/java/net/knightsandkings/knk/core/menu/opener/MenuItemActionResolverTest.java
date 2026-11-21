package net.knightsandkings.knk.core.menu.opener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Audience;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Click;

/** Siege shop DESIGN § 5.7 / A30 (PandiO/knk-workspace#871): situation + click → action. */
class MenuItemActionResolverTest {

    private static final MenuItemAction MAIN = MenuItemAction.openMenu("main");
    private static final MenuItemAction INFORMATION = MenuItemAction.openMenu("siege.information");
    private static final MenuItemAction SHOP = MenuItemAction.openMenu("siege.shop");

    private final UUID player = UUID.randomUUID();
    private final Set<UUID> inMatch = new HashSet<>();
    private final Set<UUID> inSurvivalMatch = new HashSet<>();
    private final Set<UUID> atHub = new HashSet<>();
    private final MenuItemSituations situations = new MenuItemSituations();
    private final MenuItemActionResolver resolver = new MenuItemActionResolver(situations);

    @BeforeEach
    void setUp() {
        situations.provide(MenuItemSituations.SIEGE_MATCH, inMatch::contains);
        situations.provide(MenuItemSituations.SIEGE_SURVIVAL_MATCH, inSurvivalMatch::contains);
        situations.provide(MenuItemSituations.SIEGE_HUB, atHub::contains);
    }

    /** The API's seeded defaults (knk-web-api {@code MenuItemSettings.Defaults()}, DESIGN § 4.3.4). */
    static MenuOpenerSettings seeded() {
        Map<Click, MenuItemAction> clicks = new EnumMap<>(Click.class);
        for (Click click : Click.values()) {
            clicks.put(click, MAIN);
        }
        Map<String, Map<Click, MenuItemAction>> overrides = new LinkedHashMap<>();
        overrides.put(MenuItemSituations.SIEGE_HUB, Map.of(Click.LEFT_CLICK, INFORMATION, Click.RIGHT_CLICK, INFORMATION));
        Map<Click, MenuItemAction> match = Map.of(Click.LEFT_CLICK, INFORMATION, Click.RIGHT_CLICK, INFORMATION,
            Click.SHIFT_RIGHT_CLICK, SHOP);
        overrides.put(MenuItemSituations.SIEGE_MATCH, match);
        overrides.put(MenuItemSituations.SIEGE_SURVIVAL_MATCH, match);
        return MenuOpenerSettings.withActions(true, Audience.ALL, true, true, "NETHER_STAR", null, List.of(), 8, false,
            false, clicks, overrides);
    }

    @Test
    void outsideASiegeEveryClickOpensTheMainMenu_A30() {
        for (Click click : Click.values()) {
            assertEquals(MAIN, resolver.resolve(seeded(), player, click), click.name());
        }
    }

    @Test
    void inAPvpMatchClicksOpenTheSiegeMenuAndShiftRightTheShop_A30() {
        inMatch.add(player);
        assertEquals(INFORMATION, resolver.resolve(seeded(), player, Click.LEFT_CLICK));
        assertEquals(INFORMATION, resolver.resolve(seeded(), player, Click.RIGHT_CLICK));
        assertEquals(SHOP, resolver.resolve(seeded(), player, Click.SHIFT_RIGHT_CLICK));
        assertEquals(MAIN, resolver.resolve(seeded(), player, Click.SHIFT_LEFT_CLICK), "not in the override: the default table");
        assertEquals(MAIN, resolver.resolve(seeded(), player, Click.INVENTORY_CLICK));
    }

    @Test
    void inASurvivalMatchTheSameOverrideApplies_A30() {
        inSurvivalMatch.add(player);
        assertEquals(SHOP, resolver.resolve(seeded(), player, Click.SHIFT_RIGHT_CLICK));
        assertEquals(INFORMATION, resolver.resolve(seeded(), player, Click.LEFT_CLICK));
    }

    @Test
    void atTheSiegeHubLeftAndRightOpenTheSiegeMenu() {
        atHub.add(player);
        assertEquals(INFORMATION, resolver.resolve(seeded(), player, Click.RIGHT_CLICK));
        assertEquals(MAIN, resolver.resolve(seeded(), player, Click.SHIFT_RIGHT_CLICK));
    }

    @Test
    void theMostSpecificSituationWins_AndOnlyItsOverrideCounts() {
        inSurvivalMatch.add(player);
        inMatch.add(player);
        atHub.add(player);
        Map<Click, MenuItemAction> clicks = Map.of(Click.LEFT_CLICK, MAIN);
        Map<String, Map<Click, MenuItemAction>> overrides = new LinkedHashMap<>();
        overrides.put(MenuItemSituations.SIEGE_HUB, Map.of(Click.LEFT_CLICK, INFORMATION, Click.RIGHT_CLICK, INFORMATION));
        overrides.put(MenuItemSituations.SIEGE_SURVIVAL_MATCH, Map.of(Click.LEFT_CLICK, MenuItemAction.runCommand("siege buy")));
        MenuOpenerSettings s = MenuOpenerSettings.withActions(true, Audience.ALL, true, true, null, null, null, 8, false,
            false, clicks, overrides);
        assertEquals(MenuItemAction.runCommand("siege buy"), resolver.resolve(s, player, Click.LEFT_CLICK));
        assertEquals(MenuItemAction.none(), resolver.resolve(s, player, Click.RIGHT_CLICK),
            "the hub override is not consulted for a survival match member; the default table has no right click");
    }

    @Test
    void aSituationWithoutAnOverrideUsesTheDefaultTable_AndAMissingClickDoesNothing() {
        inMatch.add(player);
        MenuOpenerSettings s = MenuOpenerSettings.withActions(true, Audience.ALL, true, true, null, null, null, 8, false,
            false, Map.of(Click.LEFT_CLICK, MAIN), Map.of(MenuItemSituations.SIEGE_HUB, Map.of(Click.LEFT_CLICK, INFORMATION)));
        assertEquals(MAIN, resolver.resolve(s, player, Click.LEFT_CLICK));
        assertTrue(resolver.resolve(s, player, Click.RIGHT_CLICK).isNone());
        assertTrue(resolver.resolve(null, player, Click.LEFT_CLICK).isNone());
    }

    @Test
    void theConfigFileSettingsNeverAskForASituation_A31() {
        AtomicInteger asked = new AtomicInteger();
        situations.provide(MenuItemSituations.SIEGE_MATCH, id -> {
            asked.incrementAndGet();
            return true;
        });
        MenuOpenerSettings file = MenuOpenerSettings.defaults();
        for (Click click : Click.values()) {
            assertEquals(MAIN, resolver.resolve(file, player, click));
        }
        assertEquals(0, asked.get(), "no overrides, so the click path reads nothing else");
    }
}
