package net.knightsandkings.knk.core.domain.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionalTest {

    @Test
    void modifiedCarriesBodyAndEtag() {
        Conditional<String> fresh = Conditional.modified("graph", "\"3\"");
        assertFalse(fresh.notModified());
        assertEquals("graph", fresh.body());
        assertEquals("\"3\"", fresh.etag());
        assertEquals("graph", fresh.bodyOptional().orElseThrow());
    }

    @Test
    void notModifiedHasNoBody() {
        Conditional<String> same = Conditional.notModified("\"3\"");
        assertTrue(same.notModified());
        assertNull(same.body());
        assertTrue(same.bodyOptional().isEmpty());
        assertEquals("\"3\"", same.etag());
    }

    @Test
    void refusesContradictoryStates() {
        assertThrows(IllegalArgumentException.class, () -> new Conditional<>(true, "body", null));
        assertThrows(NullPointerException.class, () -> new Conditional<String>(false, null, "\"1\""));
    }
}
