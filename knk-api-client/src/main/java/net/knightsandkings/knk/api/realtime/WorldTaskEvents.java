package net.knightsandkings.knk.api.realtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * The WT2 world-task events of the game hub (PandiO/knk-workspace#181, DESIGN §8.3–§8.4) and their payloads, as sent by
 * {@code knk-web-api/Realtime/Contracts/WorldTaskContracts.cs}. Each {@code parse} returns null for a payload it can't
 * use (no task id, bad UUID); the handler then ignores the event. Payloads never carry the task code.
 */
public final class WorldTaskEvents {

    public static final String WORLD_TASK_OFFERED = "WorldTaskOffered";
    public static final String WORLD_TASK_CANCELLED = "WorldTaskCancelled";
    public static final String WORLD_TASK_OFFER_WITHDRAWN = "WorldTaskOfferWithdrawn";
    public static final String WORLD_TASK_CREATED = "WorldTaskCreated";
    /** Game server → API (hub invocation). */
    public static final String WORLD_TASK_OFFER_RESULT = "WorldTaskOfferResult";

    public static final List<String> TYPES = List.of(WORLD_TASK_OFFERED, WORLD_TASK_CANCELLED, WORLD_TASK_OFFER_WITHDRAWN,
        WORLD_TASK_CREATED);

    /** {@code mode} of an offer. */
    public static final String MODE_AUTO_CLAIM = "auto-claim";
    public static final String MODE_ACCEPT = "accept";

    private WorldTaskEvents() {
    }

    /**
     * {@code WorldTaskOffered}: offer one task to one player. {@code mode} {@value #MODE_AUTO_CLAIM} (they sent it from the
     * web app: claim at once) or {@value #MODE_ACCEPT} (someone handed it to them: Accept/Decline within
     * {@code timeoutSeconds}).
     */
    public record Offered(int taskId, UUID uuid, String taskType, String fieldName, String label, String entityType,
                          String mode, String fromUsername, int timeoutSeconds) {
        public boolean autoClaim() {
            return !MODE_ACCEPT.equals(mode);
        }

        public static Offered parse(RealtimeEnvelope envelope) {
            JsonNode p = envelope.payload();
            Integer taskId = intField(p, "taskId");
            UUID uuid = uuidField(p, "uuid");
            if (taskId == null || taskId <= 0 || uuid == null) {
                return null;
            }
            Integer timeout = intField(p, "timeoutSeconds");
            String label = textField(p, "label");
            return new Offered(taskId, uuid, textField(p, "taskType"), textField(p, "fieldName"),
                label == null || label.isBlank() ? "world task #" + taskId : label, textField(p, "entityType"),
                textField(p, "mode"), textField(p, "fromUsername"), timeout == null ? 120 : Math.max(10, timeout));
        }
    }

    /** {@code WorldTaskCancelled}: status now Cancelled, Expired, or Pending (released by the web app's "Replace"). */
    public record Cancelled(int taskId, String status, UUID uuid, String reason) {
        public static Cancelled parse(RealtimeEnvelope envelope) {
            JsonNode p = envelope.payload();
            Integer taskId = intField(p, "taskId");
            return taskId == null || taskId <= 0 ? null
                : new Cancelled(taskId, textField(p, "status"), uuidField(p, "uuid"), textField(p, "reason"));
        }
    }

    /** {@code WorldTaskOfferWithdrawn}: the offer is no longer open (claimed with the code, timed out). */
    public record OfferWithdrawn(int taskId, UUID uuid, String reason) {
        public static OfferWithdrawn parse(RealtimeEnvelope envelope) {
            JsonNode p = envelope.payload();
            Integer taskId = intField(p, "taskId");
            return taskId == null || taskId <= 0 ? null : new OfferWithdrawn(taskId, uuidField(p, "uuid"), textField(p, "reason"));
        }
    }

    /** {@code WorldTaskCreated}: a headless task (scan) waits to be claimed. */
    public record Created(int taskId, String taskType) {
        public static Created parse(RealtimeEnvelope envelope) {
            Integer taskId = intField(envelope.payload(), "taskId");
            return taskId == null || taskId <= 0 ? null : new Created(taskId, textField(envelope.payload(), "taskType"));
        }
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
