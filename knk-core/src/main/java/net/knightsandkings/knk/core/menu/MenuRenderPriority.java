package net.knightsandkings.knk.core.menu;

/**
 * FR-2.4.2 render priority (layering when sections overlap), mirrors
 * knk-web-api's {@code MenuRenderPriority}. Sections render in ascending
 * priority order so HIGH-priority sections paint last/on top, per
 * ARCHITECTURE_DESIGN.md §3.4.
 */
public enum MenuRenderPriority {
    LOW,
    MEDIUM,
    HIGH
}
