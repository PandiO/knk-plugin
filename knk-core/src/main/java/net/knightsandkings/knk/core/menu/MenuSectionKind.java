package net.knightsandkings.knk.core.menu;

/**
 * Preset section type, mirrors knk-web-api's {@code MenuSectionKind}. Phase 2
 * assembles and lays out a section identically regardless of kind - the
 * actual preset renderer/field-schema library keyed off this value is
 * Phase 7's job (IMPLEMENTATION_PLAN.md); this phase just carries the value
 * through losslessly so Phase 7 has it to dispatch on.
 */
public enum MenuSectionKind {
    CONTENT_GRID,
    SEARCH_BAR,
    FILTER_BAR,
    STATIC_BUTTONS,
    CONFIRM_DIALOG
}
