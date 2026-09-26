package net.knightsandkings.knk.core.domain.users;

import java.time.OffsetDateTime;

/**
 * Result of a salary payout call (docs/specs/user-features/IMPLEMENTATION_PLAN.md §6). Always
 * returned, whether or not a payout actually happened - paid() distinguishes the two; every
 * multiplier/coin field is 0 when paid() is false.
 */
public record SalaryPayoutResult(
    boolean paid,
    int amountPaid,
    double hoursCovered,
    double globalMultiplier,
    double personalMultiplier,
    double rankMultiplier,
    int newCoinsBalance,
    OffsetDateTime lastSalaryPayoutAt,
    OffsetDateTime nextEligibleAt,
    // The title bracket whose Salary was the hourly base rate, and that Salary before any
    // multiplier. Null/0 when paid() is false or the API predates the title-based salary fix.
    Integer titleBracketId,
    int titleSalary
) {}
