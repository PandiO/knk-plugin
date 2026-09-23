package net.knightsandkings.knk.core.menu;

/**
 * FR-2.1.1 menu growth behavior, mirrors knk-web-api's {@code MenuGrowthMode}.
 * Phase 2 only implements STATIC (the menu's Inventory size is always its
 * declared {@code height}); DYNAMIC is parsed and preserved but not yet acted
 * on - resizing an already-open Inventory based on content is a general
 * flex-solving concern DESIGN_REVIEW.md §1 explicitly says to temper, not the
 * static-grid layout this phase builds.
 */
public enum MenuGrowth {
    STATIC,
    DYNAMIC
}
