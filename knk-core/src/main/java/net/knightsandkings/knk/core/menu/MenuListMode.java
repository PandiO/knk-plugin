package net.knightsandkings.knk.core.menu;

/**
 * FR-2.4.4 list modes, mirrors knk-web-api's {@code MenuListMode}. Phase 2
 * parses and carries this value but fills every section with one canonical
 * row-major order over its calculated slots (see {@link MenuSlotCalculator}),
 * regardless of mode - a single static-grid fill algorithm, not three
 * different layout algorithms, per DESIGN_REVIEW.md §1. Distinct arrangement
 * aesthetics per mode are preset-library (Phase 7) territory.
 */
public enum MenuListMode {
    DEFAULT,
    LINEAR,
    GRID
}
