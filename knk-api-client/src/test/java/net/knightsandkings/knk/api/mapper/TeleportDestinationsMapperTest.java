package net.knightsandkings.knk.api.mapper;

import net.knightsandkings.knk.api.dto.TeleportDestinationDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Destination names as players see and type them (KNG-42 smoke test: a stored trailing line break). */
class TeleportDestinationsMapperTest {

    @Test
    void lineBreaksAndExtraWhitespaceAreRemovedFromNames() {
        assertEquals("Merchant's District", TeleportDestinationsMapper.displayName("Merchant's District\n"));
        assertEquals("Merchant's District", TeleportDestinationsMapper.displayName("  Merchant's\r\n\tDistrict \u2028"));
        assertEquals("Kardenna", TeleportDestinationsMapper.displayName("Kardenna"));
        assertNull(TeleportDestinationsMapper.displayName(" \n "));
        assertNull(TeleportDestinationsMapper.displayName(null));
    }

    @Test
    void aRowWithOnlyWhitespaceAsItsNameIsDropped() {
        assertNull(TeleportDestinationsMapper.map(new TeleportDestinationDto(3, "\n", "District", null, null, null,
            null, null, null, null, null, null, null, null, null)));
    }
}
