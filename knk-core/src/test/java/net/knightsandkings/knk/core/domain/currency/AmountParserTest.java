package net.knightsandkings.knk.core.domain.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** /pay amounts (currency-payments IMPLEMENTATION_PLAN.md Phase 3 plugin tests). */
class AmountParserTest {

    @ParameterizedTest
    @ValueSource(strings = {"-5", "0", "1.5", "1e3", "1,000", "99999999999", "1000000000", "+5", " 5", "5 ", "", "05", "0x10", "١٢", "5k"})
    void rejects(String raw) {
        assertTrue(AmountParser.parse(raw).isEmpty(), raw);
    }

    @Test
    void rejectsNull() {
        assertTrue(AmountParser.parse(null).isEmpty());
    }

    @Test
    void acceptsWholeNumbersUpToTheCap() {
        assertEquals(1, AmountParser.parse("1").getAsLong());
        assertEquals(2500, AmountParser.parse("2500").getAsLong());
        assertEquals(999_999_999L, AmountParser.parse("999999999").getAsLong());
    }
}
