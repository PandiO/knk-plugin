package net.knightsandkings.knk.core.menu;

/**
 * FR-2.4.3 overflow handling, mirrors knk-web-api's {@code MenuOverflowMode}.
 * <p>
 * Phase 2 implementation call (base-class pagination per IMPLEMENTATION_PLAN.md
 * / reconciliation gap #9): {@link #HIDE} truncates a section's content to one
 * page's worth of slots with no page navigation. {@link #SCROLL} and
 * {@link #WRAP} both enable real multi-page pagination on
 * {@code RuntimeMenuSection} - the distinction between a "scrolling" and a
 * "wrapping" presentation is a presentation-layer nuance left to the preset
 * section-type library (Phase 7), not something the generic base-class
 * static-grid layout (DESIGN_REVIEW.md §1) needs to differentiate.
 */
public enum MenuOverflowMode {
    SCROLL,
    HIDE,
    WRAP
}
