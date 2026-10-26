package net.knightsandkings.knk.api.realtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.knightsandkings.knk.api.dto.PlayerNotificationDto;
import net.knightsandkings.knk.api.mapper.UsersMapper;
import net.knightsandkings.knk.core.domain.users.PlayerNotification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The S2 user-state events of the game hub (PandiO/knk-workspace#179, DESIGN §6.1) and their payloads, as sent by
 * {@code knk-web-api/Realtime/Contracts/RealtimeEnvelope.cs} ({@code RealtimeEventTypes}). Each {@code parse} returns
 * null for a payload it can't use (missing ids, bad UUID); the handler then ignores the event, and the next resync
 * catches up.
 */
public final class UserStateEvents {

    public static final String USER_PERMISSIONS_CHANGED = "UserPermissionsChanged";
    public static final String PERMISSION_GROUP_CHANGED = "PermissionGroupChanged";
    public static final String USER_BALANCES_CHANGED = "UserBalancesChanged";
    public static final String USER_FROZEN_CHANGED = "UserFrozenChanged";
    public static final String USER_MODE_CHANGED = "UserModeChanged";
    public static final String USER_CHANGED = "UserChanged";
    /** One queued player notification, the same object {@code GET api/PlayerNotifications/pending} returns. */
    public static final String PLAYER_NOTIFICATION = "PlayerNotification";

    /** Every type above, version 1. */
    public static final List<String> TYPES = List.of(USER_PERMISSIONS_CHANGED, PERMISSION_GROUP_CHANGED,
        USER_BALANCES_CHANGED, USER_FROZEN_CHANGED, USER_MODE_CHANGED, USER_CHANGED, PLAYER_NOTIFICATION);

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .registerModule(new JavaTimeModule());

    private UserStateEvents() {
    }

    /** {@code UserPermissionsChanged}: memberships or direct grants of one user changed. */
    public record PermissionsChanged(int userId, UUID uuid, String reason) {
        public static PermissionsChanged parse(RealtimeEnvelope envelope) {
            JsonNode p = envelope.payload();
            Integer userId = intField(p, "userId");
            UUID uuid = uuidField(p, "uuid");
            return userId == null || uuid == null ? null : new PermissionsChanged(userId, uuid, textField(p, "reason"));
        }
    }

    /** {@code PermissionGroupChanged}: a group, its parent or its node grants changed ({@code op}: Created, Updated, Deleted, GrantsChanged). */
    public record GroupChanged(int groupId, String op) {
        public static final String CREATED = "Created";

        public static GroupChanged parse(RealtimeEnvelope envelope) {
            Integer groupId = intField(envelope.payload(), "groupId");
            return groupId == null ? null : new GroupChanged(groupId, textField(envelope.payload(), "op"));
        }
    }

    /** {@code UserBalancesChanged}: the committed balances after the change. */
    public record BalancesChanged(int userId, UUID uuid, long coins, long gems, long xp) {
        public static BalancesChanged parse(RealtimeEnvelope envelope) {
            JsonNode p = envelope.payload();
            Integer userId = intField(p, "userId");
            UUID uuid = uuidField(p, "uuid");
            if (userId == null || uuid == null || !hasNumber(p, "coins") || !hasNumber(p, "gems") || !hasNumber(p, "xp")) {
                return null;
            }
            return new BalancesChanged(userId, uuid, p.get("coins").asLong(), p.get("gems").asLong(), p.get("xp").asLong());
        }
    }

    /** {@code UserFrozenChanged}: frozen or unfrozen by staff ({@code by} = their user id, may be null). */
    public record FrozenChanged(int userId, UUID uuid, boolean frozen, String reason, Integer by) {
        public static FrozenChanged parse(RealtimeEnvelope envelope) {
            JsonNode p = envelope.payload();
            Integer userId = intField(p, "userId");
            UUID uuid = uuidField(p, "uuid");
            JsonNode frozen = p == null ? null : p.get("frozen");
            if (userId == null || uuid == null || frozen == null || !frozen.isBoolean()) {
                return null;
            }
            return new FrozenChanged(userId, uuid, frozen.asBoolean(), textField(p, "reason"), intField(p, "by"));
        }
    }

    /** {@code UserModeChanged}: the API's {@code ActiveMode} name ({@code None}, {@code Staff}, {@code Owner}). */
    public record ModeChanged(int userId, UUID uuid, String mode) {
        public static ModeChanged parse(RealtimeEnvelope envelope) {
            JsonNode p = envelope.payload();
            Integer userId = intField(p, "userId");
            UUID uuid = uuidField(p, "uuid");
            String mode = textField(p, "mode");
            return userId == null || uuid == null || mode == null ? null : new ModeChanged(userId, uuid, mode);
        }
    }

    /** {@code UserChanged}: other cached fields changed (multipliers, gate method, link, merge). */
    public record UserChanged(int userId, UUID uuid, List<String> fields) {
        public static UserChanged parse(RealtimeEnvelope envelope) {
            JsonNode p = envelope.payload();
            Integer userId = intField(p, "userId");
            UUID uuid = uuidField(p, "uuid");
            if (userId == null || uuid == null) {
                return null;
            }
            List<String> fields = new ArrayList<>();
            JsonNode array = p.get("fields");
            if (array != null && array.isArray()) {
                array.forEach(node -> fields.add(node.asText()));
            }
            return new UserChanged(userId, uuid, List.copyOf(fields));
        }
    }

    /** {@code PlayerNotification}: the notification as the REST poll would return it, or null when unreadable. */
    public static PlayerNotification parseNotification(RealtimeEnvelope envelope) {
        JsonNode p = envelope.payload();
        if (p == null || !p.isObject() || intField(p, "id") == null || textField(p, "type") == null) {
            return null;
        }
        try {
            return UsersMapper.mapPlayerNotification(MAPPER.treeToValue(p, PlayerNotificationDto.class));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasNumber(JsonNode p, String field) {
        return p != null && p.get(field) != null && p.get(field).isNumber();
    }

    private static Integer intField(JsonNode p, String field) {
        JsonNode node = p == null ? null : p.get(field);
        return node != null && node.canConvertToInt() && node.isIntegralNumber() ? node.asInt() : null;
    }

    private static String textField(JsonNode p, String field) {
        JsonNode node = p == null ? null : p.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static UUID uuidField(JsonNode p, String field) {
        String text = textField(p, field);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
