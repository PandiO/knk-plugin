package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkMenuItemTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuSectionTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link MenuTemplateAssembler}: composite tree assembly from the
 * Phase 1 persisted-template shape into the Phase 2 runtime tree, including
 * the loud, per-menu (not per-server) failure policy DESIGN_REVIEW.md §1
 * decided on for bad template data.
 */
class MenuTemplateAssemblerTest {

    private static KnkMenuItemTemplate item(int id, int sortOrder) {
        return new KnkMenuItemTemplate(id, sortOrder, null, 5, 1, null, null, "Normal", null, null,
                List.of(new KnkVariableBinding(id * 10, "Name", 0, "Item " + id, "Static", null)),
                List.of(), List.of());
    }

    private static KnkMenuSectionTemplate section(int id, String kind, int sortOrder, int displaySlot,
                                                    int width, int height, List<KnkMenuItemTemplate> items) {
        return new KnkMenuSectionTemplate(id, "section-" + id, kind, sortOrder, displaySlot, width, height,
                "Static", "Top", "Left", "Hide", "Default", "Medium", null, null, items, List.of(), null, null);
    }

    @Test
    void assemblesAWellFormedTemplateSuccessfully() {
        KnkMenuTemplate template = new KnkMenuTemplate(1, "example.placeholder", "Example", "desc", 3, "Static", null,
                List.of(section(1, "StaticButtons", 0, 0, 9, 1, List.of(item(1, 0)))));

        RuntimeMenu menu = MenuTemplateAssembler.assemble(template);

        assertEquals("example.placeholder", menu.key());
        assertEquals(3, menu.height());
        assertEquals(MenuGrowth.STATIC, menu.growth());
        assertEquals(1, menu.sections().size());
        assertEquals(MenuSectionKind.STATIC_BUTTONS, menu.sections().get(0).kind());
    }

    @Test
    void sectionsAndItemsAreOrderedBySortOrderRegardlessOfInputOrder() {
        KnkMenuTemplate template = new KnkMenuTemplate(1, "k", "n", null, 3, "Static", null,
                List.of(
                        section(2, "ContentGrid", 1, 9, 9, 1, List.of(item(20, 1), item(10, 0))),
                        section(1, "ContentGrid", 0, 0, 9, 1, List.of())
                ));

        RuntimeMenu menu = MenuTemplateAssembler.assemble(template);

        assertEquals(List.of(1, 2), menu.sections().stream().map(RuntimeMenuSection::id).toList());
        assertEquals(List.of(10, 20), menu.sections().get(1).items().stream().map(RuntimeMenuItem::id).toList());
    }

    @Test
    void nullOptionalFieldsFallBackToSchemaDefaults() {
        KnkMenuSectionTemplate rawSection = new KnkMenuSectionTemplate(1, "s", null, null, null, null, null,
                null, null, null, null, null, null, null, null, List.of(), List.of(), null, null);
        KnkMenuTemplate template = new KnkMenuTemplate(1, "k", "n", null, null, null, null, List.of(rawSection));

        RuntimeMenu menu = MenuTemplateAssembler.assemble(template);

        assertEquals(3, menu.height());
        assertEquals(MenuGrowth.STATIC, menu.growth());
        RuntimeMenuSection section = menu.sections().get(0);
        assertEquals(MenuSectionKind.CONTENT_GRID, section.kind());
        assertEquals(MenuAlignVertical.TOP, section.alignVertical());
        assertEquals(MenuAlignHorizontal.LEFT, section.alignHorizontal());
        assertEquals(MenuOverflowMode.HIDE, section.overflow());
        assertEquals(9, section.width());
        assertEquals(1, section.height());
        assertFalse(section.searchable());
    }

    @Test
    void searchableFlagCarriesThroughToTheRuntimeSection() {
        KnkMenuSectionTemplate rawSection = new KnkMenuSectionTemplate(1, "s", "ContentGrid", 0, 0, 9, 1,
                "Static", "Top", "Left", "Hide", "Default", "Medium", null, true, List.of(), List.of(), null, null);
        KnkMenuTemplate template = new KnkMenuTemplate(1, "k", "n", null, 3, "Static", null, List.of(rawSection));

        RuntimeMenu menu = MenuTemplateAssembler.assemble(template);

        assertTrue(menu.sections().get(0).searchable());
    }

    @Test
    void unrecognizedEnumValueFailsJustThisMenuWithAClearMessage() {
        KnkMenuTemplate template = new KnkMenuTemplate(1, "broken.menu", "n", null, 3, "Static", null,
                List.of(section(1, "NotAKind", 0, 0, 9, 1, List.of())));

        MenuAssemblyException ex = assertThrows(MenuAssemblyException.class, () -> MenuTemplateAssembler.assemble(template));

        assertTrue(ex.getMessage().contains("NotAKind"));
        assertTrue(ex.getMessage().contains("broken.menu"));
    }

    @Test
    void invalidMenuHeightFailsValidation() {
        KnkMenuTemplate template = new KnkMenuTemplate(1, "k", "n", null, 10, "Static", null, List.of());

        MenuAssemblyException ex = assertThrows(MenuAssemblyException.class, () -> MenuTemplateAssembler.assemble(template));

        assertTrue(ex.getMessage().contains("height"));
    }

    @Test
    void sectionThatDoesNotFitWithinMenuBoundsFailsValidation() {
        // width=9 anchored at column 5 of a 3-row/27-slot menu can't fit (bug #8's guard).
        KnkMenuTemplate template = new KnkMenuTemplate(1, "k", "n", null, 3, "Static", null,
                List.of(section(1, "ContentGrid", 0, 5, 9, 1, List.of())));

        assertThrows(MenuAssemblyException.class, () -> MenuTemplateAssembler.assemble(template));
    }

    @Test
    void overlappingSectionsFailValidation() {
        KnkMenuTemplate template = new KnkMenuTemplate(1, "k", "n", null, 3, "Static", null,
                List.of(
                        section(1, "ContentGrid", 0, 0, 5, 1, List.of()),
                        section(2, "ContentGrid", 1, 3, 5, 1, List.of())
                ));

        MenuAssemblyException ex = assertThrows(MenuAssemblyException.class, () -> MenuTemplateAssembler.assemble(template));

        assertTrue(ex.getMessage().contains("occupied by both"));
    }

    @Test
    void blankKeyIsRejected() {
        KnkMenuTemplate template = new KnkMenuTemplate(1, "", "n", null, 3, "Static", null, List.of());

        assertThrows(MenuAssemblyException.class, () -> MenuTemplateAssembler.assemble(template));
    }
}
