package net.knightsandkings.knk.core.domain.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.users.BalanceCurrency;

class CurrencyFormatTest {

    @Test
    void usesUsGrouping() {
        assertEquals("1,234,567", CurrencyFormat.amount(1_234_567));
        assertEquals("0", CurrencyFormat.amount(0));
        assertEquals("-250", CurrencyFormat.signed(-250));
        assertEquals("+1,000", CurrencyFormat.signed(1000));
    }

    @Test
    void namesAgreeWithTheAmount() {
        assertEquals("coin", CurrencyFormat.name(BalanceCurrency.COINS, 1));
        assertEquals("coins", CurrencyFormat.name(BalanceCurrency.COINS, 2));
        assertEquals("gems", CurrencyFormat.name(BalanceCurrency.GEMS, 0));
        assertEquals("XP", CurrencyFormat.name(BalanceCurrency.EXPERIENCE, 1));
    }

    @Test
    void durationsUseTwoUnitsAtMost() {
        assertEquals("5s", CurrencyFormat.duration(Duration.ofSeconds(5)));
        assertEquals("1s", CurrencyFormat.duration(Duration.ofMillis(200)));
        assertEquals("3m 20s", CurrencyFormat.duration(Duration.ofSeconds(200)));
        assertEquals("2h 5m", CurrencyFormat.duration(Duration.ofMinutes(125)));
        assertEquals("1d 4h", CurrencyFormat.duration(Duration.ofHours(28)));
        assertEquals("0s", CurrencyFormat.duration(Duration.ofSeconds(-3)));
    }

    @Test
    void currencyErrorReadsDetails() {
        CurrencyError error = new CurrencyError("DailyCapExceeded", "DailyCapExceeded: Daily limit reached",
            java.util.Map.of("remaining", 1500, "resetsAt", "2026-09-27T10:00:00Z"));
        assertEquals(1500L, error.detailLong("remaining"));
        assertEquals("Daily limit reached", error.plainMessage());
        assertEquals(null, error.detailLong("missing"));
    }
}
