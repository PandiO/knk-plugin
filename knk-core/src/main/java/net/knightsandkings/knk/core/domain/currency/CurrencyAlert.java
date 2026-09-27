package net.knightsandkings.knk.core.domain.currency;

import java.time.Instant;

/**
 * A currency anomaly alert raised by knk-web-api's currency monitor (currency-payments DESIGN.md
 * §3.9, Phase 5): rules R1-R9, severity Low/Medium/High/Critical. Read-only here - the plugin
 * lists them ({@code /knk currency alerts}) and acknowledges them; the API decides everything.
 */
public record CurrencyAlert(
    long id,
    String rule,          // R1..R9
    String ruleName,      // e.g. "Funnel"
    String severity,      // Low, Medium, High, Critical
    String summary,
    Integer userId,       // the player concerned, if any
    String username,
    String transactionPublicId,
    Instant createdAt,
    Instant ackedAt,
    String ackedByUsername
) {
    public boolean acknowledged() {
        return ackedAt != null;
    }
}
