package net.knightsandkings.knk.core.domain.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AlreadyReversedTest {

    @Test
    void readsTheReversalFromTheDetails() {
        AlreadyReversed info = AlreadyReversed.from(new CurrencyError("AlreadyReversed", "AlreadyReversed: already", Map.of(
            "reversalTransactionPublicId", "01M4", "reversedAt", "2026-09-27T10:15:00.123Z",
            "reversedByUserId", 42, "reversedByUsername", "Owner")));

        assertEquals("01M4", info.reversalPublicId());
        assertEquals(Instant.parse("2026-09-27T10:15:00.123Z"), info.reversedAt());
        assertEquals("Owner", info.reversedBy());
    }

    @Test
    void aMissingNameFallsBackToTheIdOrTheServer_andAnOffsetlessTimeIsUtc() {
        Map<String, Object> details = new HashMap<>();
        details.put("reversalTransactionPublicId", "01M4");
        details.put("reversedAt", "2026-09-27T10:15:00");
        details.put("reversedByUserId", 42);
        details.put("reversedByUsername", null);
        AlreadyReversed byId = AlreadyReversed.from(new CurrencyError("AlreadyReversed", "x", details));
        assertEquals("#42", byId.reversedBy());
        assertEquals(Instant.parse("2026-09-27T10:15:00Z"), byId.reversedAt());

        details.put("reversedByUserId", null);
        assertEquals("the game server", AlreadyReversed.from(new CurrencyError("AlreadyReversed", "x", details)).reversedBy());
    }

    @Test
    void otherErrors_orNoReversalId_giveNothing() {
        assertNull(AlreadyReversed.from(new CurrencyError("NotReversible", "x", Map.of("reversalTransactionPublicId", "01M4"))));
        assertNull(AlreadyReversed.from(new CurrencyError("AlreadyReversed", "x", Map.of())));
        assertNull(AlreadyReversed.from(null));
    }
}
