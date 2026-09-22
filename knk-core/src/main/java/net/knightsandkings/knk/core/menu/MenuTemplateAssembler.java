package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkMenuItemTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuSectionTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Composite tree assembly (IMPLEMENTATION_PLAN.md Phase 2): builds the
 * rendered-instance {@link RuntimeMenu} tree from a persisted
 * {@link KnkMenuTemplate} (loaded via {@code MenuTemplatesDataAccess} in
 * Phase 1), parsing its enum-shaped String fields and sorting children by
 * their explicit {@code sortOrder}. Throws {@link MenuAssemblyException} for
 * any single menu that can't be assembled or that fails
 * {@link MenuLayoutValidator} - per DESIGN_REVIEW.md §1's decided failure
 * policy, this is meant to be caught by the menu-open flow and refused
 * per-menu, not allowed to bring anything else down.
 */
public final class MenuTemplateAssembler {

    private static final int DEFAULT_HEIGHT = 3;

    private MenuTemplateAssembler() {
    }

    public static RuntimeMenu assemble(KnkMenuTemplate template) {
        Objects.requireNonNull(template, "template must not be null");
        if (template.key() == null || template.key().isBlank()) {
            throw new MenuAssemblyException("Menu template (id " + template.id() + ") has no key");
        }

        String context = "menu '" + template.key() + "'";
        int height = template.height() != null ? template.height() : DEFAULT_HEIGHT;
        MenuGrowth growth = MenuEnumParsing.parse(MenuGrowth.class, template.growth(), MenuGrowth.STATIC, "growth", context);

        List<KnkMenuSectionTemplate> sourceSections = template.sections() != null ? template.sections() : List.of();
        List<RuntimeMenuSection> sections = new ArrayList<>(sourceSections.size());
        for (KnkMenuSectionTemplate sectionTemplate : sourceSections) {
            sections.add(assembleSection(sectionTemplate, template.key()));
        }
        sections.sort(Comparator.comparingInt(RuntimeMenuSection::sortOrder));

        RuntimeMenu menu = new RuntimeMenu(template.key(), template.name(), height, growth,
                template.backgroundMaterialRefId(), List.copyOf(sections));

        MenuLayoutValidator.validate(menu);

        return menu;
    }

    private static RuntimeMenuSection assembleSection(KnkMenuSectionTemplate sectionTemplate, String menuKey) {
        String context = "section '" + sectionTemplate.name() + "' (id " + sectionTemplate.id()
                + ") in menu '" + menuKey + "'";

        MenuSectionKind kind = MenuEnumParsing.parse(
                MenuSectionKind.class, sectionTemplate.kind(), MenuSectionKind.CONTENT_GRID, "kind", context);
        MenuPositionMode positionMode = MenuEnumParsing.parse(
                MenuPositionMode.class, sectionTemplate.positionMode(), MenuPositionMode.STATIC, "positionMode", context);
        MenuAlignVertical alignVertical = MenuEnumParsing.parse(
                MenuAlignVertical.class, sectionTemplate.alignVertical(), MenuAlignVertical.TOP, "alignVertical", context);
        MenuAlignHorizontal alignHorizontal = MenuEnumParsing.parse(
                MenuAlignHorizontal.class, sectionTemplate.alignHorizontal(), MenuAlignHorizontal.LEFT, "alignHorizontal", context);
        MenuOverflowMode overflow = MenuEnumParsing.parse(
                MenuOverflowMode.class, sectionTemplate.overflow(), MenuOverflowMode.HIDE, "overflow", context);
        MenuListMode listMode = MenuEnumParsing.parse(
                MenuListMode.class, sectionTemplate.listMode(), MenuListMode.DEFAULT, "listMode", context);
        MenuRenderPriority priority = MenuEnumParsing.parse(
                MenuRenderPriority.class, sectionTemplate.priority(), MenuRenderPriority.MEDIUM, "priority", context);

        int width = sectionTemplate.width() != null ? sectionTemplate.width() : MenuSlotCalculator.MENU_WIDTH;
        int height = sectionTemplate.height() != null ? sectionTemplate.height() : 1;
        int displaySlot = sectionTemplate.displaySlot() != null ? sectionTemplate.displaySlot() : 0;
        int sortOrder = sectionTemplate.sortOrder() != null ? sectionTemplate.sortOrder() : 0;
        boolean searchable = sectionTemplate.searchable() != null && sectionTemplate.searchable();

        List<KnkMenuItemTemplate> sourceItems = sectionTemplate.items() != null ? sectionTemplate.items() : List.of();
        List<RuntimeMenuItem> items = new ArrayList<>(sourceItems.size());
        for (KnkMenuItemTemplate itemTemplate : sourceItems) {
            items.add(assembleItem(itemTemplate, sectionTemplate.name(), menuKey));
        }
        items.sort(Comparator.comparingInt(RuntimeMenuItem::sortOrder));

        return new RuntimeMenuSection(
                sectionTemplate.id(), sectionTemplate.name(), kind, sortOrder, displaySlot, width, height,
                positionMode, alignVertical, alignHorizontal, overflow, listMode, priority,
                sectionTemplate.visibilityPermission(), searchable, List.copyOf(items),
                sectionTemplate.variableBindings() != null ? sectionTemplate.variableBindings() : List.of()
        );
    }

    private static RuntimeMenuItem assembleItem(KnkMenuItemTemplate itemTemplate, String sectionName, String menuKey) {
        String context = "item (id " + itemTemplate.id() + ") in section '" + sectionName
                + "' in menu '" + menuKey + "'";

        MenuDisplayMode displayMode = MenuEnumParsing.parse(
                MenuDisplayMode.class, itemTemplate.displayMode(), MenuDisplayMode.NORMAL, "displayMode", context);

        int sortOrder = itemTemplate.sortOrder() != null ? itemTemplate.sortOrder() : 0;
        int amount = itemTemplate.amount() != null ? itemTemplate.amount() : 1;

        return new RuntimeMenuItem(
                itemTemplate.id(), sortOrder, itemTemplate.slotOverride(), itemTemplate.materialRefId(), amount,
                itemTemplate.chatColorName(), itemTemplate.chatColorDescription(), displayMode,
                itemTemplate.visibilityPermission(), itemTemplate.actionPermission(),
                itemTemplate.variableBindings() != null ? itemTemplate.variableBindings() : List.of(),
                itemTemplate.actions() != null ? itemTemplate.actions() : List.of(),
                itemTemplate.conditions() != null ? itemTemplate.conditions() : List.of()
        );
    }
}
