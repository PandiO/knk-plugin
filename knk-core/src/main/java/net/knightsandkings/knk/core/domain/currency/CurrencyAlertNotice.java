package net.knightsandkings.knk.core.domain.currency;

import java.util.List;

/**
 * Payload of a CurrencyAlert player notification (currency Phase 5): a new alert for every
 * online staff member holding knk.admin.currency.alerts. {@code transfersDisabled}: currencies
 * whose player transfers the alert switched off (a reconciliation mismatch, rule R1).
 */
public record CurrencyAlertNotice(
    long alertId,
    String rule,
    String ruleName,
    String severity,
    String summary,
    Integer userId,
    String username,
    List<String> transfersDisabled
) {
    public CurrencyAlertNotice {
        transfersDisabled = transfersDisabled == null ? List.of() : List.copyOf(transfersDisabled);
    }
}
