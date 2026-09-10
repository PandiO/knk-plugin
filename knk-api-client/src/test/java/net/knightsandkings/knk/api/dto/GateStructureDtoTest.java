package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GateStructureDtoTest {

    @Test
    void deserializesLocationObjectsReturnedByTheGateStructuresApi() throws Exception {
        String json = "{\"id\":10,\"name\":\"Keep Gate test\",\"anchorPoint\":{\"x\":1420,\"y\":85,\"z\":-522}}";

        GateStructureDto gate = new ObjectMapper().readValue(json, GateStructureDto.class);

        assertEquals(10, gate.getId());
        assertEquals("{\"x\":1420,\"y\":85,\"z\":-522}", gate.getAnchorPoint());
    }

    @Test
    void deserializesOpenAnchorPointTheSameWayAsAnchorPoint() throws Exception {
        String json = "{\"id\":14,\"name\":\"Drawbridge\",\"openAnchorPoint\":{\"x\":10,\"y\":64,\"z\":20}}";

        GateStructureDto gate = new ObjectMapper().readValue(json, GateStructureDto.class);

        assertEquals("{\"x\":10,\"y\":64,\"z\":20}", gate.getOpenAnchorPoint());
    }
}