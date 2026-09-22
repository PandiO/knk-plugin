package net.knightsandkings.knk.core.menu;

/**
 * Result of a click-time condition check (IMPLEMENTATION_PLAN.md Phase 6,
 * DESIGN_REVIEW.md §2.2): whether the gated item/action may proceed, plus an
 * optional player-facing denial message the condition handler itself
 * supplies. A null message means "deny silently" - the same policy Phase 4
 * already uses for {@code actionPermission} (don't reveal exactly what's
 * missing); a condition handler opts into player-facing feedback by
 * returning one instead, per DESIGN_REVIEW.md §2.2's own "you can't afford
 * this" example of the kind of failure a player should be told about.
 */
public record ConditionOutcome(boolean allowed, String denialMessage) {

    public static ConditionOutcome allow() {
        return new ConditionOutcome(true, null);
    }

    public static ConditionOutcome deny() {
        return new ConditionOutcome(false, null);
    }

    public static ConditionOutcome deny(String denialMessage) {
        return new ConditionOutcome(false, denialMessage);
    }
}
