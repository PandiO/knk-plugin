package net.knightsandkings.knk.api.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PandiO/knk-workspace#179: the S2 payloads as knk-web-api sends them (camelCase, Realtime/Contracts) parse into the
 * records the handlers use; anything unusable is null.
 */
class UserStateEventsTest {

    private static final String UUID_TEXT = "00000000-0000-0000-0000-0000000000a1";
    private final ObjectMapper mapper = new ObjectMapper();

    private RealtimeEnvelope envelope(String type, String payload) {
        return RealtimeEnvelope.parse("{\"v\":1,\"seq\":3,\"type\":\"" + type + "\",\"at\":\"2026-10-26T12:00:00Z\","
            + "\"actor\":{\"userId\":9,\"source\":\"web\"},\"payload\":" + payload + "}", mapper);
    }

    @Test
    void permissionsChanged() {
        var event = UserStateEvents.PermissionsChanged.parse(envelope(UserStateEvents.USER_PERMISSIONS_CHANGED,
            "{\"userId\":42,\"uuid\":\"" + UUID_TEXT + "\",\"reason\":\"GroupAssigned\"}"));
        assertEquals(new UserStateEvents.PermissionsChanged(42, UUID.fromString(UUID_TEXT), "GroupAssigned"), event);
    }

    @Test
    void groupChanged() {
        var event = UserStateEvents.GroupChanged.parse(envelope(UserStateEvents.PERMISSION_GROUP_CHANGED,
            "{\"groupId\":40,\"op\":\"GrantsChanged\"}"));
        assertEquals(new UserStateEvents.GroupChanged(40, "GrantsChanged"), event);
    }

    @Test
    void balancesChanged() {
        var event = UserStateEvents.BalancesChanged.parse(envelope(UserStateEvents.USER_BALANCES_CHANGED,
            "{\"userId\":42,\"uuid\":\"" + UUID_TEXT + "\",\"coins\":125,\"gems\":4,\"xp\":50}"));
        assertEquals(new UserStateEvents.BalancesChanged(42, UUID.fromString(UUID_TEXT), 125, 4, 50), event);
        assertNull(UserStateEvents.BalancesChanged.parse(envelope(UserStateEvents.USER_BALANCES_CHANGED,
            "{\"userId\":42,\"uuid\":\"" + UUID_TEXT + "\",\"coins\":125}")));
    }

    @Test
    void frozenChanged_WithAndWithoutReason() {
        var frozen = UserStateEvents.FrozenChanged.parse(envelope(UserStateEvents.USER_FROZEN_CHANGED,
            "{\"userId\":42,\"uuid\":\"" + UUID_TEXT + "\",\"frozen\":true,\"reason\":\"griefing\",\"by\":9}"));
        assertTrue(frozen.frozen());
        assertEquals("griefing", frozen.reason());
        assertEquals(9, frozen.by());
        var unfrozen = UserStateEvents.FrozenChanged.parse(envelope(UserStateEvents.USER_FROZEN_CHANGED,
            "{\"userId\":42,\"uuid\":\"" + UUID_TEXT + "\",\"frozen\":false,\"reason\":null,\"by\":null}"));
        assertFalse(unfrozen.frozen());
        assertNull(unfrozen.reason());
        assertNull(unfrozen.by());
    }

    @Test
    void modeAndUserChanged() {
        assertEquals("Staff", UserStateEvents.ModeChanged.parse(envelope(UserStateEvents.USER_MODE_CHANGED,
            "{\"userId\":42,\"uuid\":\"" + UUID_TEXT + "\",\"mode\":\"Staff\"}")).mode());
        assertEquals(List.of("multipliers", "link"), UserStateEvents.UserChanged.parse(envelope(UserStateEvents.USER_CHANGED,
            "{\"userId\":42,\"uuid\":\"" + UUID_TEXT + "\",\"fields\":[\"multipliers\",\"link\"]}")).fields());
    }

    @Test
    void unusablePayloadsAreNull() {
        assertNull(UserStateEvents.PermissionsChanged.parse(envelope(UserStateEvents.USER_PERMISSIONS_CHANGED,
            "{\"userId\":42,\"uuid\":\"not-a-uuid\"}")));
        assertNull(UserStateEvents.PermissionsChanged.parse(envelope(UserStateEvents.USER_PERMISSIONS_CHANGED, "null")));
        assertNull(UserStateEvents.GroupChanged.parse(envelope(UserStateEvents.PERMISSION_GROUP_CHANGED, "{\"op\":\"Updated\"}")));
        assertNull(UserStateEvents.FrozenChanged.parse(envelope(UserStateEvents.USER_FROZEN_CHANGED,
            "{\"userId\":42,\"uuid\":\"" + UUID_TEXT + "\"}")));
        assertNull(UserStateEvents.parseNotification(envelope(UserStateEvents.PLAYER_NOTIFICATION, "{\"userId\":1}")));
    }

    @Test
    void playerNotificationIsTheSameObjectThePollReturns() {
        PlayerNotification notification = UserStateEvents.parseNotification(envelope(UserStateEvents.PLAYER_NOTIFICATION,
            "{\"id\":17,\"userId\":42,\"uuid\":\"" + UUID_TEXT + "\",\"username\":\"alice\",\"type\":\"RankChanged\","
                + "\"createdAt\":\"2026-10-26T12:00:00Z\",\"titleChange\":null}"));
        assertEquals(17L, notification.id());
        assertEquals(42, notification.userId());
        assertEquals(UUID_TEXT, notification.uuid());
        assertEquals(PlayerNotification.TYPE_RANK_CHANGED, notification.type());
    }

    @Test
    void everyTypeIsListed() {
        assertEquals(7, UserStateEvents.TYPES.size());
        assertTrue(UserStateEvents.TYPES.contains(UserStateEvents.PLAYER_NOTIFICATION));
    }
}
