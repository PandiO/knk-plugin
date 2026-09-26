package net.knightsandkings.knk.core.domain.users;

import java.time.OffsetDateTime;
import java.util.List;

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
    int titleSalary,
    // Hours of salary the gap was worth after log decay, titleSalary x paidHours (the amount
    // before multipliers), and every multiplier applied: global, personal, then one per rank.
    // 0/0/empty when the API predates KNG-16's payout message.
    double paidHours,
    double baseAmount,
    List<RewardMultiplier> multipliers
) {
    public SalaryPayoutResult {
        multipliers = multipliers == null ? List.of() : List.copyOf(multipliers);
    }
}
