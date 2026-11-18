package net.knightsandkings.knk.api.realtime;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Kick and ban from the web app (PandiO/knk-workspace#565 / #574, DESIGN §6.10): {@code PlayerKick} and
 * {@code PlayerBan}, version 1, sent to every game server by {@code knk-web-api/Services/PlayerModerationService.cs}
 * with the same payload ({@code PlayerModerationPayload}, camelCase).
 */
public final class ModerationEvents {

    public static final String PLAYER_KICK = "PlayerKick";
    public static final String PLAYER_BAN = "PlayerBan";

    private ModerationEvents() {
    }

    /**
     * One staff action.
     *
     * @param actionId      unique per staff action; a receiver acts once per id
     * @param userId        the knk user
     * @param uuid          their Minecraft UUID: what the receiver acts on
     * @param username      their username (messages, logs, the ban list profile's name); may be null
     * @param reason        shown to the player; null for a kick without a reason (the default message is used)
     * @param staffUserId   the staff member, null when missing
     * @param staffUsername the staff member's username (the ban list's source); may be null
     * @param at            when the API accepted it (ISO-8601 UTC text); may be null
     */
    public record PlayerModeration(String actionId, int userId, UUID uuid, String username, String reason,
                                   Integer staffUserId, String staffUsername, String at) {

        /** The action, or null when the payload has no action id, user id or usable UUID. */
        public static PlayerModeration parse(RealtimeEnvelope envelope) {
            JsonNode p = envelope == null ? null : envelope.payload();
            if (p == null || !p.isObject()) {
                return null;
            }
            String actionId = text(p, "actionId");
            JsonNode userId = p.get("userId");
            UUID uuid = PrivacyEvents.uuidOrNull(text(p, "uuid"));
            if (actionId == null || actionId.isBlank() || uuid == null
                || userId == null || !userId.isIntegralNumber() || !userId.canConvertToInt()) {
                return null;
            }
            JsonNode staff = p.get("staffUserId");
            Integer staffUserId = staff != null && staff.isIntegralNumber() && staff.canConvertToInt() ? staff.asInt() : null;
            return new PlayerModeration(actionId.trim(), userId.asInt(), uuid, text(p, "username"), blankToNull(text(p, "reason")),
                staffUserId, text(p, "staffUsername"), text(p, "at"));
        }
    }

    private static String text(JsonNode p, String field) {
        JsonNode node = p.get(field);
        return node == null || node.isNull() || node.isContainerNode() ? null : node.asText();
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
