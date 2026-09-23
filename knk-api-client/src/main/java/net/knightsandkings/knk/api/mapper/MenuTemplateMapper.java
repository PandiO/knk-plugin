package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.ActionBindingDto;
import net.knightsandkings.knk.api.dto.ConditionBindingDto;
import net.knightsandkings.knk.api.dto.MenuItemTemplateDto;
import net.knightsandkings.knk.api.dto.MenuSectionTemplateDto;
import net.knightsandkings.knk.api.dto.MenuTemplateDto;
import net.knightsandkings.knk.api.dto.MenuTemplateListDto;
import net.knightsandkings.knk.api.dto.VariableBindingDto;
import net.knightsandkings.knk.core.domain.menu.KnkActionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkConditionBinding;
import net.knightsandkings.knk.core.domain.menu.KnkMenuItemTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuSectionTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplate;
import net.knightsandkings.knk.core.domain.menu.KnkMenuTemplateSummary;
import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;

import java.util.Collections;
import java.util.List;

public final class MenuTemplateMapper {
    private MenuTemplateMapper() {}

    public static KnkMenuTemplate toCore(MenuTemplateDto dto) {
        if (dto == null) return null;

        List<KnkMenuSectionTemplate> sections = dto.sections() == null
                ? Collections.emptyList()
                : dto.sections().stream().map(MenuTemplateMapper::toCore).toList();

        return new KnkMenuTemplate(
                dto.id(),
                dto.key(),
                dto.name(),
                dto.description(),
                dto.height(),
                dto.growth(),
                dto.backgroundMaterialRefId(),
                sections
        );
    }

    public static KnkMenuTemplateSummary toCore(MenuTemplateListDto dto) {
        if (dto == null) return null;

        return new KnkMenuTemplateSummary(
                dto.id(),
                dto.key(),
                dto.name(),
                dto.description(),
                dto.sectionCount()
        );
    }

    public static KnkMenuSectionTemplate toCore(MenuSectionTemplateDto dto) {
        if (dto == null) return null;

        List<KnkMenuItemTemplate> items = dto.items() == null
                ? Collections.emptyList()
                : dto.items().stream().map(MenuTemplateMapper::toCore).toList();

        List<KnkVariableBinding> bindings = dto.variableBindings() == null
                ? Collections.emptyList()
                : dto.variableBindings().stream().map(MenuTemplateMapper::toCore).toList();

        return new KnkMenuSectionTemplate(
                dto.id(),
                dto.name(),
                dto.kind(),
                dto.sortOrder(),
                dto.displaySlot(),
                dto.width(),
                dto.height(),
                dto.positionMode(),
                dto.alignVertical(),
                dto.alignHorizontal(),
                dto.overflow(),
                dto.listMode(),
                dto.priority(),
                dto.visibilityPermission(),
                dto.searchable(),
                items,
                bindings,
                dto.contentSourceId(),
                dto.contentSourceParamsJson()
        );
    }

    public static KnkMenuItemTemplate toCore(MenuItemTemplateDto dto) {
        if (dto == null) return null;

        List<KnkVariableBinding> bindings = dto.variableBindings() == null
                ? Collections.emptyList()
                : dto.variableBindings().stream().map(MenuTemplateMapper::toCore).toList();

        List<KnkActionBinding> actions = dto.actions() == null
                ? Collections.emptyList()
                : dto.actions().stream().map(MenuTemplateMapper::toCore).toList();

        List<KnkConditionBinding> conditions = dto.conditions() == null
                ? Collections.emptyList()
                : dto.conditions().stream().map(MenuTemplateMapper::toCore).toList();

        return new KnkMenuItemTemplate(
                dto.id(),
                dto.sortOrder(),
                dto.slotOverride(),
                dto.materialRefId(),
                dto.amount(),
                dto.chatColorName(),
                dto.chatColorDescription(),
                dto.displayMode(),
                dto.visibilityPermission(),
                dto.actionPermission(),
                bindings,
                actions,
                conditions
        );
    }

    public static KnkVariableBinding toCore(VariableBindingDto dto) {
        if (dto == null) return null;

        return new KnkVariableBinding(
                dto.id(),
                dto.targetProperty(),
                dto.sortOrder(),
                dto.expression(),
                dto.refreshPolicy(),
                dto.ttlTicks()
        );
    }

    public static KnkActionBinding toCore(ActionBindingDto dto) {
        if (dto == null) return null;

        List<KnkConditionBinding> conditions = dto.conditions() == null
                ? Collections.emptyList()
                : dto.conditions().stream().map(MenuTemplateMapper::toCore).toList();

        return new KnkActionBinding(
                dto.id(),
                dto.actionTypeId(),
                dto.paramsJson(),
                dto.sortOrder(),
                conditions
        );
    }

    public static KnkConditionBinding toCore(ConditionBindingDto dto) {
        if (dto == null) return null;

        return new KnkConditionBinding(
                dto.id(),
                dto.conditionTypeId(),
                dto.paramsJson(),
                dto.sortOrder()
        );
    }
}
