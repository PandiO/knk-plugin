package net.knightsandkings.knk.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.realtime.GateCommandEvents.GateCommand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** PandiO/knk-workspace#741: {@code GateCommand} v1 as knk-web-api sends it ({@code GateCommandPayload}, camelCase). */
class GateCommandEventsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private RealtimeEnvelope envelope(String payload) {
        return RealtimeEnvelope.parse("{\"v\":1,\"seq\":3,\"type\":\"GateCommand\",\"at\":\"2026-11-18T12:00:00Z\","
            + "\"actor\":{\"userId\":7,\"source\":\"web\"},\"payload\":" + payload + "}", mapper);
    }

    @Test
    void asTheApiSendsIt() {
        assertEquals(new GateCommand("c1", 12, 21, "open", 7, "GateKeeper"), GateCommand.parse(envelope(
            "{\"commandId\":\"c1\",\"gateId\":12,\"doorId\":21,\"action\":\"open\",\"staffUserId\":7,\"staffUsername\":\"GateKeeper\"}")));
    }

    @Test
    void withoutADoor_DoorIdIsNull() {
        assertNull(GateCommand.parse(envelope("{\"commandId\":\"c2\",\"gateId\":12,\"doorId\":null,\"action\":\"close\"}")).doorId());
        assertNull(GateCommand.parse(envelope("{\"commandId\":\"c3\",\"gateId\":12,\"action\":\"close\",\"extra\":1}")).doorId());
    }

    @Test
    void unusablePayloads_AreNull() {
        assertNull(GateCommand.parse(envelope("{\"gateId\":12,\"action\":\"open\"}")));
        assertNull(GateCommand.parse(envelope("{\"commandId\":\" \",\"gateId\":12,\"action\":\"open\"}")));
        assertNull(GateCommand.parse(envelope("{\"commandId\":\"c\",\"gateId\":0,\"action\":\"open\"}")));
        assertNull(GateCommand.parse(envelope("{\"commandId\":\"c\",\"gateId\":\"12\",\"action\":\"open\"}")));
        assertNull(GateCommand.parse(envelope("{\"commandId\":\"c\",\"gateId\":12}")));
        assertNull(GateCommand.parse(envelope("null")));
        assertNull(GateCommand.parse(null));
    }
}
