package net.knightsandkings.knk.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.realtime.ModerationEvents.PlayerModeration;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** PandiO/knk-workspace#574: {@code PlayerKick} / {@code PlayerBan} v1 as knk-web-api sends them (DESIGN §6.10). */
class ModerationEventsTest {

    private static final String UUID_TEXT = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    private final ObjectMapper mapper = new ObjectMapper();

    private RealtimeEnvelope envelope(String type, String payload) {
        return RealtimeEnvelope.parse("{\"v\":1,\"seq\":7,\"type\":\"" + type + "\",\"at\":\"2026-11-15T12:00:00Z\","
            + "\"actor\":{\"userId\":7,\"source\":\"web\"},\"payload\":" + payload + "}", mapper);
    }

    @Test
    void banAsDocumented() {
        PlayerModeration action = PlayerModeration.parse(envelope(ModerationEvents.PLAYER_BAN,
            "{\"actionId\":\"5b0c1d2e-0000-4000-8000-000000000001\",\"userId\":42,\"uuid\":\"" + UUID_TEXT + "\","
                + "\"username\":\"Steve\",\"reason\":\"X-ray\",\"staffUserId\":7,\"staffUsername\":\"Mod\","
                + "\"at\":\"2026-11-15T12:00:00Z\"}"));
        assertEquals(new PlayerModeration("5b0c1d2e-0000-4000-8000-000000000001", 42, UUID.fromString(UUID_TEXT), "Steve",
            "X-ray", 7, "Mod", "2026-11-15T12:00:00Z"), action);
    }

    @Test
    void kickWithoutReason_ReasonIsNull() {
        PlayerModeration action = PlayerModeration.parse(envelope(ModerationEvents.PLAYER_KICK,
            "{\"actionId\":\"a1\",\"userId\":42,\"uuid\":\"" + UUID_TEXT + "\",\"username\":\"Steve\",\"reason\":null,"
                + "\"staffUserId\":7,\"staffUsername\":\"Mod\",\"at\":\"2026-11-15T12:00:00Z\"}"));
        assertNull(action.reason());
        assertEquals("a1", action.actionId());
        // A blank reason is treated as none (the default message), never as an empty kick screen.
        assertNull(PlayerModeration.parse(envelope(ModerationEvents.PLAYER_KICK,
            "{\"actionId\":\"a2\",\"userId\":42,\"uuid\":\"" + UUID_TEXT + "\",\"reason\":\"  \"}")).reason());
    }

    @Test
    void optionalFieldsMissing_StillParsed() {
        PlayerModeration action = PlayerModeration.parse(envelope(ModerationEvents.PLAYER_KICK,
            "{\"actionId\":\"a3\",\"userId\":42,\"uuid\":\"" + UUID_TEXT + "\",\"unknownField\":true}"));
        assertEquals(new PlayerModeration("a3", 42, UUID.fromString(UUID_TEXT), null, null, null, null, null), action);
    }

    @Test
    void unusable_IsNull() {
        assertNull(PlayerModeration.parse(envelope(ModerationEvents.PLAYER_BAN,
            "{\"userId\":42,\"uuid\":\"" + UUID_TEXT + "\",\"reason\":\"x\"}")), "no action id");
        assertNull(PlayerModeration.parse(envelope(ModerationEvents.PLAYER_BAN,
            "{\"actionId\":\" \",\"userId\":42,\"uuid\":\"" + UUID_TEXT + "\"}")), "blank action id");
        assertNull(PlayerModeration.parse(envelope(ModerationEvents.PLAYER_BAN,
            "{\"actionId\":\"a\",\"userId\":42,\"uuid\":\"not-a-uuid\"}")), "bad uuid");
        assertNull(PlayerModeration.parse(envelope(ModerationEvents.PLAYER_BAN,
            "{\"actionId\":\"a\",\"userId\":42}")), "no uuid");
        assertNull(PlayerModeration.parse(envelope(ModerationEvents.PLAYER_BAN,
            "{\"actionId\":\"a\",\"userId\":\"42\",\"uuid\":\"" + UUID_TEXT + "\"}")), "user id as text");
        assertNull(PlayerModeration.parse(envelope(ModerationEvents.PLAYER_BAN,
            "{\"actionId\":{\"x\":1},\"userId\":42,\"uuid\":\"" + UUID_TEXT + "\"}")), "action id as object");
        assertNull(PlayerModeration.parse(envelope(ModerationEvents.PLAYER_BAN, "null")), "no payload");
        assertNull(PlayerModeration.parse(envelope(ModerationEvents.PLAYER_BAN, "[1,2]")), "array payload");
        assertNull(PlayerModeration.parse(null));
    }
}
