package net.knightsandkings.knk.core.siege;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Siege Phase 5: display-text cleanup for the dev-DB quirks (garbled lobby name, trailing newlines). */
class SiegeDisplayTextTest {

    @Test
    void repairsUtf8TextThatWasDecodedAsWindows1252Once() {
        // The dev lobby 1 name as stored: an em dash double-encoded.
        assertEquals("[TEST] Siege — Cinix", SiegeDisplayText.clean("[TEST] Siege â€” Cinix"));
    }

    @Test
    void stripsTrailingNewlinesAndCollapsesWhitespace() {
        assertEquals("North District", SiegeDisplayText.clean("North District\n"));
        assertEquals("A B", SiegeDisplayText.clean("  A \t\r\n  B  "));
    }

    @Test
    void leavesCorrectTextAloneEvenWithAccentsThatLookLikeMojibake() {
        assertEquals("Château Âme", SiegeDisplayText.clean("Château Âme"));
        assertEquals("Siege — Cinix", SiegeDisplayText.clean("Siege — Cinix"));
    }

    @Test
    void usesTheFallbackForNullOrBlank() {
        assertEquals("fallback", SiegeDisplayText.clean(null, "fallback"));
        assertEquals("fallback", SiegeDisplayText.clean(" \n ", "fallback"));
        assertEquals("", SiegeDisplayText.clean(null));
    }
}
