package net.knightsandkings.knk.paper.chat;

/**
 * Enum representing different chat capture flows.
 * Each flow represents a different account management scenario.
 */
public enum CaptureFlow {
    /**
     * Account merge flow: Display accounts → Choice (A or B)
     */
    ACCOUNT_MERGE,

    /**
     * IMPLEMENTATION_PLAN.md Phase 5: a single free-text line captured for a
     * caller-supplied purpose (e.g. an InventoryMenu search query or filter
     * value) - the reusable, idiomatic alternative to a one-off anvil-GUI
     * text input, per DESIGN_REVIEW.md §2.1's "pick one input pattern as the
     * standard rather than leaving it per-screen".
     */
    TEXT_INPUT
}
