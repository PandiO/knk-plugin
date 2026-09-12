package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Item 5 (docs/features/gate-structure-animation/GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md) moved
 * per-door fields (anchorPoint, etc. - see GateDoorDtoTest) off GateStructureDto onto the new
 * GateDoorDto, embedded here as gateDoors. This covers what's left: structure identity, the
 * cascading override fields, and the embedded doors list.
 */
class GateStructureDtoTest {

    @Test
    void deserializesIdAndNameAndEmbeddedGateDoors() throws Exception {
        String json = "{\"id\":14,\"name\":\"Northern Gate\","
            + "\"gateDoors\":[{\"id\":140,\"gateStructureId\":14,\"name\":\"Drawbridge\"}]}";

        GateStructureDto structure = new ObjectMapper().readValue(json, GateStructureDto.class);

        assertEquals(14, structure.getId());
        assertEquals("Northern Gate", structure.getName());
        List<GateDoorDto> doors = structure.getGateDoors();
        assertNotNull(doors);
        assertEquals(1, doors.size());
        assertEquals(140, doors.get(0).getId());
        assertEquals("Drawbridge", doors.get(0).getName());
    }

    @Test
    void deserializesStructureLevelCascadingOverrides() throws Exception {
        // Decision 5.0-B: null means "no override" - only fields actually present in the JSON
        // should come back non-null.
        String json = "{\"id\":14,\"name\":\"Northern Gate\","
            + "\"isActiveOverride\":true,\"isDestroyedOverride\":false,\"openedStateOverride\":\"OPEN\"}";

        GateStructureDto structure = new ObjectMapper().readValue(json, GateStructureDto.class);

        assertEquals(Boolean.TRUE, structure.getIsActiveOverride());
        assertEquals(Boolean.FALSE, structure.getIsDestroyedOverride());
        assertEquals("OPEN", structure.getOpenedStateOverride());
        assertEquals(null, structure.getIsInvincibleOverride());
    }
}
