package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Item 5 (docs/features/gate-structure-animation/GATESTRUCTURE_QOL_IMPLEMENTATION_PLAN.md) moved
 * the point fields (anchorPoint, openAnchorPoint, etc.) from GateStructureDto onto GateDoorDto -
 * this test (moved from GateStructureDtoTest) covers the same CoordinateStringDeserializer
 * behavior on its new owner.
 */
class GateDoorDtoTest {

    @Test
    void deserializesLocationObjectsReturnedByTheGateDoorsApi() throws Exception {
        String json = "{\"id\":10,\"name\":\"Keep Door test\",\"anchorPoint\":{\"x\":1420,\"y\":85,\"z\":-522}}";

        GateDoorDto door = new ObjectMapper().readValue(json, GateDoorDto.class);

        assertEquals(10, door.getId());
        assertEquals("{\"x\":1420,\"y\":85,\"z\":-522}", door.getAnchorPoint());
    }

    @Test
    void deserializesOpenAnchorPointTheSameWayAsAnchorPoint() throws Exception {
        String json = "{\"id\":14,\"name\":\"Drawbridge\",\"openAnchorPoint\":{\"x\":10,\"y\":64,\"z\":20}}";

        GateDoorDto door = new ObjectMapper().readValue(json, GateDoorDto.class);

        assertEquals("{\"x\":10,\"y\":64,\"z\":20}", door.getOpenAnchorPoint());
    }
}
