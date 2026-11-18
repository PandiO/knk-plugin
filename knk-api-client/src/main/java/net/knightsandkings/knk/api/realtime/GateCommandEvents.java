package net.knightsandkings.knk.api.realtime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Overview map L7 (PandiO/knk-workspace#741, OVERVIEW_MAP_DESIGN § 6.2.5–6.2.6, O17): live gate commands from the web
 * app. API → game server {@code GateCommand} v1 ({@code GateCommandPayload} in
 * {@code knk-web-api/Realtime/Contracts/MapContracts.cs}, camelCase), sent to every connected game server; game server →
 * API the hub method {@code GateCommandResult} with {@code {commandId, result, message, doors}}.
 */
public final class GateCommandEvents {

    public static final String GATE_COMMAND = "GateCommand";
    public static final String GATE_COMMAND_RESULT = "GateCommandResult";

    private GateCommandEvents() {
    }

    /**
     * One command.
     *
     * @param commandId     unique per command; the result names it and a receiver acts once per id
     * @param gateId        the gate structure
     * @param doorId        one door of that gate, or null for every door
     * @param action        {@code open}, {@code close}, {@code toggle} or {@code repair} (as sent; lower case)
     * @param staffUserId   who sent it, null when missing
     * @param staffUsername their username, may be null
     */
    public record GateCommand(String commandId, int gateId, Integer doorId, String action, Integer staffUserId,
                              String staffUsername) {

        /** The command, or null when the payload has no command id, gate id or action. */
        public static GateCommand parse(RealtimeEnvelope envelope) {
            JsonNode p = envelope == null ? null : envelope.payload();
            if (p == null || !p.isObject()) {
                return null;
            }
            String commandId = text(p, "commandId");
            String action = text(p, "action");
            Integer gateId = integer(p, "gateId");
            if (commandId == null || commandId.isBlank() || action == null || action.isBlank() || gateId == null || gateId <= 0) {
                return null;
            }
            return new GateCommand(commandId.trim(), gateId, integer(p, "doorId"), action.trim(), integer(p, "staffUserId"),
                text(p, "staffUsername"));
        }
    }

    private static String text(JsonNode p, String field) {
        JsonNode node = p.get(field);
        return node == null || node.isNull() || node.isContainerNode() ? null : node.asText();
    }

    private static Integer integer(JsonNode p, String field) {
        JsonNode node = p.get(field);
        return node != null && node.isIntegralNumber() && node.canConvertToInt() ? node.asInt() : null;
    }
}
