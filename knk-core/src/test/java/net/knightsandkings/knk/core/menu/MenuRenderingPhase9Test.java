package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkConditionBinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static net.knightsandkings.knk.core.menu.Phase9Fixtures.action;
import static net.knightsandkings.knk.core.menu.Phase9Fixtures.binding;
import static net.knightsandkings.knk.core.menu.Phase9Fixtures.condition;
import static net.knightsandkings.knk.core.menu.Phase9Fixtures.item;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InventoryMenu Phase 9: row rendering (E3), render conditions (E5), item-meta
 * bindings (E6), inline colour (E7) and lore omission/expansion (E8) - the
 * Bukkit-free half of a render pass ({@link MenuSectionRenderer},
 * {@link MenuItemPresentation}).
 */
class MenuRenderingPhase9Test {

    /** A condition context for tests: just the scope the condition was evaluated in. */
    record TestContext(RuntimeMenuItem item, Map<String, Object> scope) {
    }

    private final MenuSession session = new MenuSession(UUID.randomUUID());
    private final ConditionRegistry<TestContext> conditions = new ConditionRegistry<>();
    private final AtomicInteger renderConditionCalls = new AtomicInteger();

    MenuRenderingPhase9Test() {
        // Generic value-equals, the same contract knk-paper's engine condition implements.
        conditions.register("value-equals", (context, params) -> {
            renderConditionCalls.incrementAndGet();
            boolean equal = String.valueOf(params.get("value")).equalsIgnoreCase(String.valueOf(params.get("expected")));
            boolean negate = "true".equalsIgnoreCase(params.get("negate"));
            return equal != negate ? ConditionOutcome.allow() : ConditionOutcome.deny("nope");
        });
        conditions.register("explodes", (context, params) -> {
            throw new MenuActionException("broken condition");
        });
    }

    private MenuSectionRenderer.Pass<TestContext> pass() {
        return new MenuSectionRenderer.Pass<>(session, 0L, node -> true, conditions, TestContext::new);
    }

    private static final List<Phase9Fixtures.LobbyRow> ROWS = List.of(
            new Phase9Fixtures.LobbyRow(1, "One", List.of("&aJoin!"), "note one"),
            new Phase9Fixtures.LobbyRow(2, "Two", List.of(), null),
            new Phase9Fixtures.LobbyRow(3, "Three", List.of("&cFull", "&cWait"), null));

    private static RuntimeMenuSection rowsSection(RuntimeMenuItem rowTemplate) {
        return Phase9Fixtures.section(5, "Sieges", 9, 9, 1, List.of(rowTemplate), "siege.lobbies", Map.of());
    }

    // ---- E3 ----

    @Test
    void eachRowRendersThroughTheRowTemplateWithItsOwnRowRoot() {
        RuntimeMenuItem template = item(50, null, List.of(
                binding(1, "Name", 0, "&7$row.getName$", "Static", null),
                binding(2, "Lore", 0, "$row.getNote$", "OnDirty", null),
                binding(3, "Lore", 1, "$row.getHints$", "OnDirty", null)),
                List.of(action(1, "menu.open", "{\"key\":\"siege.information\",\"ctx.lobbyId\":\"$row.getLobbyId$\"}", List.of())),
                List.of(), true);
        RuntimeMenuSection section = rowsSection(template);

        List<MenuSectionRenderer.RenderedSlot> rendered = MenuSectionRenderer.renderRows(section, template,
                List.of(9, 10, 11, 12), ROWS, MenuVariableScope.of(Map.of()), pass());

        assertEquals(List.of(9, 10, 11), rendered.stream().map(MenuSectionRenderer.RenderedSlot::slot).toList());
        assertEquals("§7One", rendered.get(0).presentation().name());
        assertEquals(List.of("note one", "§aJoin!"), rendered.get(0).presentation().lore());
        assertEquals(List.of(), rendered.get(1).presentation().lore(), "null note dropped, empty list adds nothing");
        assertEquals(List.of("§cFull", "§cWait"), rendered.get(2).presentation().lore());
        assertEquals(ROWS.get(2), rendered.get(2).row());

        // The row travels with the slot so the click resolves the params against it.
        Map<String, String> clickParams = MenuParams.resolve(
                rendered.get(2).item().actions().get(0).paramsJson(), rendered.get(2).scope());
        assertEquals("3", clickParams.get("ctx.lobbyId"));
    }

    // ---- E5 ----

    @Test
    void aDenyingRenderConditionHidesThatRowOnlyAndLeavesAGap() {
        KnkConditionBinding notTwo = condition(1, "value-equals",
                "{\"value\":\"$row.getLobbyId$\",\"expected\":\"2\",\"negate\":\"true\"}", "Render");
        RuntimeMenuItem template = item(50, null, List.of(binding(1, "Name", "$row.getName$")), List.of(),
                List.of(notTwo), true);

        List<MenuSectionRenderer.RenderedSlot> rendered = MenuSectionRenderer.renderRows(rowsSection(template), template,
                List.of(9, 10, 11), ROWS, MenuVariableScope.of(Map.of()), pass());

        assertEquals(List.of(9, 11), rendered.stream().map(MenuSectionRenderer.RenderedSlot::slot).toList());
        assertEquals(3, renderConditionCalls.get(), "evaluated per row");
    }

    @Test
    void clickPhaseConditionsNeverHideAnItem() {
        RuntimeMenuItem pinned = item(1, 4, List.of(binding(1, "Name", "Join")), List.of(),
                List.of(condition(1, "value-equals", "{\"value\":\"a\",\"expected\":\"b\"}", null)), false);

        assertEquals(1, MenuSectionRenderer.renderItems(Map.of(4, pinned), MenuVariableScope.of(Map.of()), pass()).size());
        assertEquals(0, renderConditionCalls.get(), "click conditions are not evaluated at render");
    }

    @Test
    void actionLevelRenderConditionsDropOnlyThatAction() {
        MenuVariableScope scope = MenuVariableScope.of(Map.of("player", new Phase9Fixtures.FakePlayer("Steve", false)));
        KnkActionBinding opOnly = action(1, "menu.open", "{\"key\":\"x\"}", List.of(
                condition(1, "value-equals", "{\"value\":\"$player.isOp$\",\"expected\":\"true\"}", "Render")));
        KnkActionBinding everyoneElse = action(2, "menu.close", "{}", List.of(
                condition(2, "value-equals", "{\"value\":\"$player.isOp$\",\"expected\":\"true\",\"negate\":\"true\"}", "Render")));
        RuntimeMenuItem toggle = item(1, 4, List.of(binding(1, "Name", "Toggle")), List.of(opOnly, everyoneElse), List.of(), false);

        MenuSectionRenderer.RenderedSlot slot = MenuSectionRenderer.renderItems(Map.of(4, toggle), scope, pass()).get(0);

        assertEquals(List.of("menu.close"), slot.item().actions().stream().map(KnkActionBinding::actionTypeId).toList());
    }

    @Test
    void aThrowingRenderConditionHidesTheItemInsteadOfBreakingTheRender() {
        RuntimeMenuItem broken = item(1, 4, List.of(binding(1, "Name", "x")), List.of(),
                List.of(condition(1, "explodes", "{}", "Render")), false);
        RuntimeMenuItem fine = item(2, 5, List.of(binding(2, "Name", "y")));

        List<MenuSectionRenderer.RenderedSlot> rendered = MenuSectionRenderer.renderItems(
                new java.util.TreeMap<>(Map.of(4, broken, 5, fine)), MenuVariableScope.of(Map.of()), pass());

        assertEquals(List.of(5), rendered.stream().map(MenuSectionRenderer.RenderedSlot::slot).toList());
    }

    @Test
    void conditionEvaluatorFiltersByPhase() {
        List<KnkConditionBinding> mixed = List.of(
                condition(1, "value-equals", "{\"value\":\"1\",\"expected\":\"2\"}", "Click"),
                condition(2, "value-equals", "{\"value\":\"1\",\"expected\":\"1\"}", "Render"));
        TestContext context = new TestContext(null, Map.of());

        assertTrue(MenuConditionEvaluator.evaluate(mixed, MenuConditionPhase.RENDER, conditions, context, Map.of()).allowed());
        assertEquals("nope", MenuConditionEvaluator.evaluate(mixed, MenuConditionPhase.CLICK, conditions, context, Map.of())
                .denialMessage());
    }

    // ---- E6 ----

    @Test
    void itemMetaBindingsResolveIntoThePresentation() {
        RuntimeMenuItem banner = item(1, 4, List.of(
                binding(1, "Material", "$v.getMaterial$"),
                binding(2, "Amount", "$v.getCount$"),
                binding(3, "BannerPatterns", "WHITE|stripe_bottom:RED,border:black"),
                binding(4, "SkullOwner", "Notch"),
                binding(5, "DisplayMode", "highlight")));
        Map<String, Object> scope = MenuVariableScope.of(Map.of("v", new MetaView("minecraft:green_banner", 70)));

        MenuItemPresentation presentation = MenuItemPresentation.resolve(banner, session, scope, 0L, null);

        assertEquals("minecraft:green_banner", presentation.materialKey());
        assertEquals(64, presentation.amount(), "clamped to 1-64");
        assertEquals("WHITE", presentation.banner().baseColor());
        assertEquals(List.of(new BannerPatternSpec.Layer("stripe_bottom", "RED"), new BannerPatternSpec.Layer("border", "BLACK")),
                presentation.banner().layers());
        assertEquals("Notch", presentation.skullOwner());
        assertEquals(MenuDisplayMode.HIGHLIGHT, presentation.displayMode());
        assertTrue(presentation.warnings().isEmpty());
    }

    @Test
    void invalidMetaValuesFallBackAndReportAWarning() {
        RuntimeMenuItem item = item(1, 4, List.of(
                binding(1, "Amount", "lots"),
                binding(2, "DisplayMode", "SPARKLY"),
                binding(3, "Amount", 1, "0", "Static", null)));

        MenuItemPresentation presentation = MenuItemPresentation.resolve(item, session, MenuVariableScope.of(Map.of()), 0L, null);

        assertNull(presentation.amount(), "first Amount binding wins, and it's unparsable");
        assertEquals(MenuDisplayMode.NORMAL, presentation.displayMode());
        assertEquals(2, presentation.warnings().size());
    }

    @Test
    void amountBelowOneClampsToOneAndBlankMetaMeansNotSet() {
        RuntimeMenuItem item = item(1, 4, List.of(
                binding(1, "Amount", "-3"),
                binding(2, "Material", "$v.getBlank$"),
                binding(3, "SkullOwner", "$v.getNothing$")));
        Map<String, Object> scope = MenuVariableScope.of(Map.of("v", new MetaView("", 0)));

        MenuItemPresentation presentation = MenuItemPresentation.resolve(item, session, scope, 0L, null);

        assertEquals(1, presentation.amount());
        assertNull(presentation.materialKey());
        assertNull(presentation.skullOwner());
    }

    @Test
    void aDisplayModeBindingOfHiddenHidesTheItemAndDisabledTravelsToClickRouting() {
        RuntimeMenuItem hidden = item(1, 4, List.of(binding(1, "DisplayMode", "HIDDEN")));
        RuntimeMenuItem disabled = item(2, 5, List.of(binding(2, "DisplayMode", "DISABLED")));

        List<MenuSectionRenderer.RenderedSlot> rendered = MenuSectionRenderer.renderItems(
                new java.util.TreeMap<>(Map.of(4, hidden, 5, disabled)), MenuVariableScope.of(Map.of()), pass());

        assertEquals(1, rendered.size());
        assertEquals(MenuDisplayMode.DISABLED, rendered.get(0).item().displayMode());
    }

    @Test
    void bannerSpecGrammar() {
        BannerPatternSpec noBase = BannerPatternSpec.parse("stripe_top:green, minecraft:border : light_blue");
        assertNull(noBase.baseColor());
        assertEquals(List.of(new BannerPatternSpec.Layer("stripe_top", "GREEN"),
                new BannerPatternSpec.Layer("minecraft:border", "LIGHT_BLUE")), noBase.layers());

        BannerPatternSpec plainRed = BannerPatternSpec.parse("red|");
        assertEquals("RED", plainRed.baseColor());
        assertTrue(plainRed.layers().isEmpty());

        BannerPatternSpec broken = BannerPatternSpec.parse("TEAL|nocolour,stripe:NOTACOLOR,Bad Key!:RED,cross:WHITE");
        assertNull(broken.baseColor());
        assertEquals(List.of(new BannerPatternSpec.Layer("cross", "WHITE")), broken.layers());
        assertEquals(4, broken.errors().size());

        assertNull(BannerPatternSpec.parse("  "));
    }

    // ---- E7 ----

    @Test
    void inlineColourCodesTranslateAfterTheWholeLinePrefix() {
        assertEquals("§7Scenario: §aCastle", MenuTextColors.apply("GRAY", "Scenario: &aCastle"));
        assertEquals("§c§lBold red", MenuTextColors.apply(null, "&c&LBold red"));
        assertEquals("§7a §rreset", MenuTextColors.apply("gray", "a &rreset"));
        assertEquals("R & D &z stays", MenuTextColors.apply("NOT_A_COLOUR", "R & D &z stays"));
        assertEquals("Siege Minigame", MenuTextColors.strip("&eSiege §aMinigame"));
    }

    @Test
    void presentationAppliesPrefixThenInlineCodesToNameAndEveryLoreLine() {
        RuntimeMenuItem item = new RuntimeMenuItem(1, 0, 4, null, 1, "GREEN", "GRAY", MenuDisplayMode.NORMAL, null, null,
                List.of(binding(1, "Name", "Siege &7$v.getCount$"),
                        binding(2, "Lore", 0, "Joined: &a$v.getCount$", "OnDirty", null),
                        binding(3, "Lore", 1, "$v.getLines$", "OnDirty", null)),
                List.of(), List.of(), false);
        Map<String, Object> scope = MenuVariableScope.of(Map.of("v", new MetaView("x", 5)));

        MenuItemPresentation presentation = MenuItemPresentation.resolve(item, session, scope, 0L, null);

        assertEquals("§aSiege §75", presentation.name());
        assertEquals(List.of("§7Joined: §a5", "§7one", "§7§ctwo"), presentation.lore());
    }

    // ---- helpers ----

    public static final class MetaView {
        private final String material;
        private final int count;

        MetaView(String material, int count) {
            this.material = material;
            this.count = count;
        }

        public String getMaterial() {
            return material;
        }

        public int getCount() {
            return count;
        }

        public String getBlank() {
            return "  ";
        }

        public String getNothing() {
            return null;
        }

        public List<String> getLines() {
            return List.of("one", "&ctwo");
        }
    }
}
