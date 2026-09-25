package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkMenuItemTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuSectionTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static net.knightsandkings.knk.core.menu.Phase9Fixtures.action;
import static net.knightsandkings.knk.core.menu.Phase9Fixtures.binding;
import static net.knightsandkings.knk.core.menu.Phase9Fixtures.condition;
import static net.knightsandkings.knk.core.menu.Phase9Fixtures.item;
import static net.knightsandkings.knk.core.menu.Phase9Fixtures.menu;
import static net.knightsandkings.knk.core.menu.Phase9Fixtures.section;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** InventoryMenu Phase 9: validator + assembler changes for E1/E2/E3/E4/E5/E9. */
class MenuDefinitionValidatorPhase9Test {

    private static final Map<String, Class<?>> DECLARED = Map.of("player", Phase9Fixtures.FakePlayer.class);
    private static final Map<String, Class<?>> ROW_TYPES = Map.of("siege.lobbies", Phase9Fixtures.LobbyRow.class);

    private static RuntimeMenuItem rowTemplate(String... expressions) {
        List<net.knightsandkings.knk.core.domain.menu.KnkVariableBinding> bindings = new java.util.ArrayList<>();
        for (int i = 0; i < expressions.length; i++) {
            bindings.add(binding(100 + i, "Lore", i, expressions[i], "OnDirty", null));
        }
        return item(50, null, bindings, List.of(), List.of(), true);
    }

    private static RuntimeMenu lobbiesMenu(RuntimeMenuItem... items) {
        return menu("siege.overview", section(2, "Sieges", 9, 9, 1, List.of(items), "siege.lobbies", Map.of()));
    }

    @Test
    void rowChainsValidateAgainstTheSourcesDeclaredRowType() {
        assertDoesNotThrow(() -> MenuDefinitionValidator.validate(
                lobbiesMenu(rowTemplate("$row.getName$", "$row.getHints$")), DECLARED, ROW_TYPES));

        MenuAssemblyException ex = assertThrows(MenuAssemblyException.class, () -> MenuDefinitionValidator.validate(
                lobbiesMenu(rowTemplate("$row.getPhaseLabel$")), DECLARED, ROW_TYPES));
        assertTrue(ex.getMessage().contains("getPhaseLabel"));
        assertTrue(ex.getMessage().contains(Phase9Fixtures.LobbyRow.class.getName()));
    }

    @Test
    void rowOutsideARowTemplateIsRejectedWithAClearMessage() {
        RuntimeMenuItem pinned = item(1, 9, List.of(binding(1, "Name", "$row.getName$")));

        MenuAssemblyException ex = assertThrows(MenuAssemblyException.class, () -> MenuDefinitionValidator.validate(
                lobbiesMenu(pinned, rowTemplate("$row.getName$")), DECLARED, ROW_TYPES));
        assertTrue(ex.getMessage().contains("only exists on a row template"));
    }

    @Test
    void rowTemplateStructureRules() {
        // Row source without a row template.
        assertThrows(MenuAssemblyException.class, () -> MenuDefinitionValidator.validate(
                lobbiesMenu(item(1, 9, List.of(binding(1, "Name", "Prev")))), DECLARED, ROW_TYPES));
        // Two row templates.
        assertThrows(MenuAssemblyException.class, () -> MenuDefinitionValidator.validate(
                lobbiesMenu(rowTemplate("a"), rowTemplate("b")), DECLARED, ROW_TYPES));
        // Row template on an item source (no declared row type).
        assertThrows(MenuAssemblyException.class, () -> MenuDefinitionValidator.validate(
                lobbiesMenu(rowTemplate("$row.getName$")), DECLARED, Map.of()));
        // Row template without any content source.
        RuntimeMenu noSource = menu("x", section(2, "Plain", 9, 9, 1, List.of(rowTemplate("a")), null, Map.of()));
        assertThrows(MenuAssemblyException.class, () -> MenuDefinitionValidator.validate(noSource, DECLARED, ROW_TYPES));
    }

    @Test
    void engineRootsAreAlwaysDeclaredAndCtxHopsAreKeyLookups() {
        RuntimeMenuItem back = item(1, 8, List.of(
                binding(1, "Name", "&c$menu.getBackLabel$"),
                binding(2, "Lore", "&7$menu.getBackHint$"),
                binding(3, "Lore", "Page $section.getPage$/$section.getPageCount$"),
                binding(4, "Lore", "Lobby $ctx.lobbyId$ ($ctx.lobbyId.length$ chars)")));
        RuntimeMenu menu = menu("m", section(1, "Header", 0, 9, 1, List.of(back), null, Map.of()));

        assertDoesNotThrow(() -> MenuDefinitionValidator.validate(menu, DECLARED));

        RuntimeMenuItem typo = item(2, 8, List.of(binding(5, "Name", "$menu.getBackLable$")));
        assertThrows(MenuAssemblyException.class, () -> MenuDefinitionValidator.validate(
                menu("m", section(1, "Header", 0, 9, 1, List.of(typo), null, Map.of())), DECLARED));
    }

    @Test
    void placeholdersInsideParamsAreValidatedToo() {
        RuntimeMenuItem template = item(50, null, List.of(binding(1, "Name", "$row.getName$")),
                List.of(action(1, "menu.open", "{\"key\":\"x\",\"ctx.lobbyId\":\"$row.getLobbyIdd$\"}",
                        List.of(condition(1, "value-equals", "{\"value\":\"$player.getNam$\"}", "Render")))),
                List.of(condition(2, "value-equals", "{\"value\":\"$ctx.lobbyId$\"}", "Render")), true);
        RuntimeMenuSection sectionWithBadParams = section(2, "Sieges", 9, 9, 1, List.of(template), "siege.lobbies",
                Map.of("lobbyId", "$ctxx.lobbyId$"));

        MenuAssemblyException ex = assertThrows(MenuAssemblyException.class, () -> MenuDefinitionValidator.validate(
                menu("m", sectionWithBadParams), DECLARED, ROW_TYPES));

        assertTrue(ex.getMessage().contains("getLobbyIdd"));
        assertTrue(ex.getMessage().contains("getNam"));
        assertTrue(ex.getMessage().contains("ctxx"));
        assertTrue(!ex.getMessage().contains("$ctx.lobbyId$' references"), "valid ctx param is fine");
    }

    @Test
    void assemblerThreadsPhase9FieldsThrough() {
        KnkMenuItemTemplate rowItem = new KnkMenuItemTemplate(7, 0, null, null, 1, null, null, "Normal", null, null,
                List.of(), List.of(), List.of(new net.knightsandkings.knk.core.domain.menu.KnkConditionBinding(1, "always", "{}", 0, "Render")),
                true);
        KnkMenuSectionTemplate section = new KnkMenuSectionTemplate(1, "Sieges", "ContentGrid", 0, 9, 9, 1, null, null, null,
                "Scroll", null, null, null, false, List.of(rowItem), List.of(), "siege.lobbies", "{\"lobbyId\":\"$ctx.lobbyId$\"}");
        KnkMenuTemplate template = new KnkMenuTemplate(1, "siege.overview", "Siege", null, 3, "Static", null,
                List.of(section), 20);

        RuntimeMenu menu = MenuTemplateAssembler.assemble(template);

        assertEquals(20, menu.autoRefreshTicks());
        assertTrue(menu.autoRefreshes());
        assertTrue(menu.sections().get(0).rowTemplate().isPresent());
        assertEquals("$ctx.lobbyId$", menu.sections().get(0).contentSourceParams().get("lobbyId"));
        assertEquals(MenuConditionPhase.RENDER,
                MenuConditionEvaluator.phaseOf(menu.sections().get(0).items().get(0).conditions().get(0)));
        // A row template is never auto-placed by the in-memory pagination path.
        assertTrue(menu.sections().get(0).resolveSlots(menu.totalSlots(), 0).itemsBySlot().isEmpty());
    }

    @Test
    void assemblerRejectsAnUnknownConditionPhase() {
        KnkMenuItemTemplate badPhase = new KnkMenuItemTemplate(7, 0, 0, null, 1, null, null, "Normal", null, null,
                List.of(), List.of(), List.of(new net.knightsandkings.knk.core.domain.menu.KnkConditionBinding(1, "always", "{}", 0, "Hover")),
                false);
        KnkMenuSectionTemplate section = new KnkMenuSectionTemplate(1, "S", "StaticButtons", 0, 0, 9, 1, null, null, null,
                null, null, null, null, false, List.of(badPhase), List.of(), null, null);

        assertThrows(MenuAssemblyException.class, () -> MenuTemplateAssembler.assemble(
                new KnkMenuTemplate(1, "m", "M", null, 3, "Static", null, List.of(section))));
    }

    @Test
    void autoRefreshTicksDefaultsToOffForOldTemplates() {
        RuntimeMenu menu = MenuTemplateAssembler.assemble(new KnkMenuTemplate(1, "m", "M", null, 3, "Static", null, List.of()));
        assertEquals(0, menu.autoRefreshTicks());
    }
}
