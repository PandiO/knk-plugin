package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkConditionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;
import net.knightsandkings.knk.core.menu.ActionRegistry;
import net.knightsandkings.knk.core.menu.ConditionRegistry;
import net.knightsandkings.knk.core.menu.MenuAlignHorizontal;
import net.knightsandkings.knk.core.menu.MenuAlignVertical;
import net.knightsandkings.knk.core.menu.MenuContentSourceRegistry;
import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.MenuDefinitionValidator;
import net.knightsandkings.knk.core.menu.MenuDisplayMode;
import net.knightsandkings.knk.core.menu.MenuGrowth;
import net.knightsandkings.knk.core.menu.MenuListMode;
import net.knightsandkings.knk.core.menu.MenuOverflowMode;
import net.knightsandkings.knk.core.menu.MenuPositionMode;
import net.knightsandkings.knk.core.menu.MenuRenderPriority;
import net.knightsandkings.knk.core.menu.MenuSectionKind;
import net.knightsandkings.knk.core.menu.MenuVariableProviderRegistry;
import net.knightsandkings.knk.core.menu.RuntimeMenu;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
import net.knightsandkings.knk.paper.menu.example.ExampleDomainMenuFeature;
import net.knightsandkings.knk.paper.menu.example.ExampleMenuRow;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * InventoryMenu Phase 9, knk-paper side: engine defaults + the
 * {@code example.domain} demo feature registered through {@link MenuFeature},
 * the {@code menu.open} ctx / {@code menu.back} actions (E1/E9) and the
 * generic {@code value-equals} condition (E5). Bukkit types are mocked; no
 * server is needed.
 */
class MenuPhase9PaperTest {

    private static MenuFeatureRegistries registriesWithDefaultsAndDemo() {
        MenuFeatureRegistries registries = new MenuFeatureRegistries(new ActionRegistry<>(), new ConditionRegistry<>(),
                new MenuContentSourceRegistry<>(), new MenuVariableProviderRegistry<>());
        MenuVariableContext.registerDefaults(registries.variables());
        MenuActionHandlers.registerDefaults(registries.actions());
        MenuConditionHandlers.registerDefaults(registries.conditions());
        new ExampleDomainMenuFeature().registerMenuHandlers(registries);
        return registries;
    }

    @Test
    void demoFeatureRegistersItsRootsAndRowSource() {
        MenuFeatureRegistries registries = registriesWithDefaultsAndDemo();

        assertEquals(ExampleMenuRow.class, registries.contentSources().rowTypes().get("example.rows"));
        Map<String, Class<?>> types = registries.variables().declaredTypes();
        assertEquals(Player.class, types.get("player"));
        assertTrue(types.containsKey("exampleClock"));
        assertEquals(ExampleMenuRow.class, types.get("exampleSelected"));
        assertTrue(registries.actions().isRegistered("menu.back"));
        assertTrue(registries.conditions().isRegistered("value-equals"));
    }

    @Test
    void lockAllMakesLateFeatureRegistrationFail() {
        MenuFeatureRegistries registries = registriesWithDefaultsAndDemo();
        registries.lockAll();

        assertThrows(IllegalStateException.class,
                () -> new ExampleDomainMenuFeature().registerMenuHandlers(registries));
    }

    @Test
    void demoRowSourceHonoursInterpolatedParamsAndTheEngineSlicesThePage() {
        MenuFeatureRegistries registries = registriesWithDefaultsAndDemo();
        MenuContentSourceContext context = new MenuContentSourceContext(null, null, MenuContextParams.EMPTY);

        Page<?> firstPage = registries.contentSources()
                .fetch("example.rows", context, Map.of(), new PagedQuery(1, 7, null, null, false, Map.of()))
                .join().page();
        assertEquals(10, firstPage.totalCount());
        assertEquals(7, firstPage.items().size());

        Page<?> excluding = registries.contentSources()
                .fetch("example.rows", context, Map.of("excludeId", "2"), new PagedQuery(1, 18, null, null, false, Map.of()))
                .join().page();
        assertEquals(9, excluding.totalCount());
        assertFalse(excluding.items().stream().anyMatch(row -> ((ExampleMenuRow) row).getId() == 2));
    }

    @Test
    void exampleSelectedResolvesTheRowNamedByTheMenuContext() {
        MenuFeatureRegistries registries = registriesWithDefaultsAndDemo();

        Object selected = registries.variables().scope(null, MenuContextParams.of(Map.of("rowId", "4")), Map.of())
                .get("exampleSelected");
        Object none = registries.variables().scope(null, MenuContextParams.EMPTY, Map.of()).get("exampleSelected");

        assertEquals("Notch's Head", ((ExampleMenuRow) selected).getName());
        assertEquals(0, ((ExampleMenuRow) none).getId());
    }

    /**
     * Guards the seed/plugin contract: every getter chain the example.domain row
     * template and pinned items use (copied from knk-web-api's
     * MenuTemplateSeed.DomainIntegration.cs) validates against the demo
     * feature's declared types.
     */
    @Test
    void seedExpressionsValidateAgainstTheRegisteredTypes() {
        MenuFeatureRegistries registries = registriesWithDefaultsAndDemo();
        List<String> rowExpressions = List.of("$row.getMaterialKey$", "$row.getCount$", "$row.getBannerPatterns$",
                "$row.getSkullOwner$", "$row.getDisplayMode$", "&f$row.getName$",
                "&7Tag: &a$row.getTag$ &7- amount &a$row.getCount$", "$row.getNote$", "$row.getDetailLines$",
                "&8Click: open details (ctx.rowId=$row.getId$)");
        List<String> pinnedExpressions = List.of("&7Seconds since enable: &a$exampleClock.getSeconds$",
                "&7Time: &f$exampleClock.getTime$", "$player.getUniqueId$", "&a$player.getName$&7's head",
                "&c$menu.getBackLabel$", "&7$menu.getBackHint$", "&7Page &a$section.getPage$&7/&a$section.getPageCount$",
                "$exampleSelected.getNote$", "&7ctx.rowId = &a$ctx.rowId$", "&7Previous menu: &f$menu.getPreviousTitle$",
                "$exampleSelected.getMaterialKey$", "$exampleSelected.getDetailLines$");

        RuntimeMenuItem rowTemplate = new RuntimeMenuItem(1, 0, null, null, 1, null, null, MenuDisplayMode.NORMAL, null, null,
                bindings(rowExpressions),
                List.of(new KnkActionBinding(1, "menu.open", "{\"key\":\"example.domain.detail\",\"ctx.rowId\":\"$row.getId$\"}", 0, List.of())),
                List.of(new KnkConditionBinding(1, "value-equals", "{\"value\":\"$row.isHidden$\",\"expected\":\"false\"}", 0, "Render")),
                true);
        RuntimeMenuItem pinned = new RuntimeMenuItem(2, 0, 9, null, 1, null, null, MenuDisplayMode.NORMAL, null, null,
                bindings(pinnedExpressions), List.of(), List.of(
                        new KnkConditionBinding(2, "value-equals", "{\"value\":\"$player.isOp$\",\"expected\":\"true\"}", 0, "Render")),
                false);
        RuntimeMenuSection section = new RuntimeMenuSection(1, "Rows", MenuSectionKind.CONTENT_GRID, 0, 9, 9, 1,
                MenuPositionMode.STATIC, MenuAlignVertical.TOP, MenuAlignHorizontal.LEFT, MenuOverflowMode.SCROLL,
                MenuListMode.DEFAULT, MenuRenderPriority.MEDIUM, null, false, List.of(pinned, rowTemplate), List.of(),
                "example.rows", Map.of("excludeId", "$ctx.rowId$"));
        RuntimeMenu menu = new RuntimeMenu("example.domain", "Example", 3, MenuGrowth.STATIC, null, List.of(section), 20);

        assertDoesNotThrow(() -> MenuDefinitionValidator.validate(menu, registries.variables().declaredTypes(),
                registries.contentSources().rowTypes()));
        assertDoesNotThrow(() -> MenuDefinitionValidator.validateActionsAndConditions(menu,
                registries.actions().registeredIds(), registries.conditions().registeredIds()));
        assertDoesNotThrow(() -> MenuDefinitionValidator.validateContentSources(menu,
                registries.contentSources().registeredIds()));
    }

    @Test
    void menuOpenPassesCtxPrefixedParamsAndMenuBackGoesBack() {
        MenuFeatureRegistries registries = registriesWithDefaultsAndDemo();
        MenuService menuService = mock(MenuService.class);
        Player player = mock(Player.class);
        MenuActionContext context = new MenuActionContext(player, null, Map.of(), menuService, null, null, null, null);

        registries.actions().execute("menu.open", context,
                Map.of("key", "siege.information", "ctx.lobbyId", "3", "other", "ignored"));
        registries.actions().execute("menu.back", context, Map.of());

        verify(menuService).openMenu(player, "siege.information", MenuContextParams.of(Map.of("lobbyId", "3")));
        verify(menuService).goBack(player);
    }

    @Test
    void valueEqualsComparesTrimmedCaseInsensitiveAlternativesAndCanNegate() {
        ConditionRegistry<MenuActionContext> conditions = registriesWithDefaultsAndDemo().conditions();

        assertTrue(conditions.test("value-equals", null, Map.of("value", " TRUE ", "expected", "true")).allowed());
        assertTrue(conditions.test("value-equals", null, Map.of("value", "hub", "expected", "MATCHMAKING|HUB")).allowed());
        assertFalse(conditions.test("value-equals", null, Map.of("value", "true", "expected", "true", "negate", "true")).allowed());
        assertEquals("Join first", conditions.test("value-equals", null,
                Map.of("value", "false", "expected", "true", "denyMessage", "Join first")).denialMessage());
        assertThrows(RuntimeException.class, () -> conditions.test("value-equals", null, Map.of("value", "x")));
    }

    private static List<KnkVariableBinding> bindings(List<String> expressions) {
        List<KnkVariableBinding> bindings = new ArrayList<>();
        for (int i = 0; i < expressions.size(); i++) {
            bindings.add(new KnkVariableBinding(100 + i, "Lore", i, expressions.get(i), "OnDirty", null));
        }
        return bindings;
    }
}
