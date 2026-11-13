package net.knightsandkings.knk.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.core.privacy.ErasedAccount;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** PandiO/knk-workspace#184: {@code AccountErased} as knk-web-api sends it (Realtime/Contracts/PrivacyContracts.cs). */
class PrivacyEventsTest {

    private static final String UUID_TEXT = "00000000-0000-0000-0000-0000000000a1";
    private final ObjectMapper mapper = new ObjectMapper();

    private RealtimeEnvelope envelope(String payload) {
        return RealtimeEnvelope.parse("{\"v\":1,\"seq\":4,\"type\":\"AccountErased\",\"at\":\"2026-11-05T20:00:00Z\","
            + "\"actor\":{\"userId\":null,\"source\":\"system\"},\"payload\":" + payload + "}", mapper);
    }

    @Test
    void accountErased() {
        assertEquals(new ErasedAccount(12, 42, UUID.fromString(UUID_TEXT)),
            PrivacyEvents.parseAccountErased(envelope("{\"userId\":42,\"formerUuid\":\"" + UUID_TEXT + "\",\"erasureId\":12}")));
    }

    @Test
    void withoutAUsableUuid_OnlyTheUserIdIsKept() {
        assertEquals(new ErasedAccount(13, 43, null),
            PrivacyEvents.parseAccountErased(envelope("{\"userId\":43,\"formerUuid\":null,\"erasureId\":13}")));
        assertEquals(new ErasedAccount(14, 44, null),
            PrivacyEvents.parseAccountErased(envelope("{\"userId\":44,\"formerUuid\":\"not-a-uuid\",\"erasureId\":14}")));
    }

    @Test
    void unusable_IsNull() {
        assertNull(PrivacyEvents.parseAccountErased(envelope("{\"userId\":42,\"formerUuid\":\"" + UUID_TEXT + "\"}")));
        assertNull(PrivacyEvents.parseAccountErased(envelope("{\"userId\":42,\"erasureId\":0}")));
        assertNull(PrivacyEvents.parseAccountErased(envelope("{\"userId\":\"x\",\"erasureId\":3}")));
        assertNull(PrivacyEvents.parseAccountErased(envelope("null")));
    }
}
