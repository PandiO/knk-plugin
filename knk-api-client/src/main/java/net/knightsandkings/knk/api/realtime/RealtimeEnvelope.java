package net.knightsandkings.knk.api.realtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Every message the API's game hub sends (PandiO/knk-workspace#178, DESIGN §4.2): one hub method per event type with
 * this single argument, {@code { v, seq, type, at, actor, payload }}. Mirrors
 * {@code knk-web-api/Realtime/Contracts/RealtimeEnvelope.cs}.
 *
 * @param v       contract version of {@code type}; a handler ignores a version it doesn't know
 * @param seq     per connection, 1, 2, 3, ...; a jump means lost messages (resync)
 * @param type    event type (= hub method name)
 * @param at      when the API published it (ISO-8601 UTC)
 * @param actor   who caused it ({@code userId}, {@code source}), may be null
 * @param payload the event's data ("this changed": ids and a few display fields), may be null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RealtimeEnvelope(
    @JsonProperty("v") int v,
    @JsonProperty("seq") long seq,
    @JsonProperty("type") String type,
    @JsonProperty("at") String at,
    @JsonProperty("actor") JsonNode actor,
    @JsonProperty("payload") JsonNode payload
) {
    /** Parses one envelope; throws {@link IllegalArgumentException} on malformed JSON. */
    public static RealtimeEnvelope parse(String json, ObjectMapper mapper) {
        try {
            RealtimeEnvelope envelope = mapper.readValue(json, RealtimeEnvelope.class);
            if (envelope == null) {
                throw new IllegalArgumentException("Empty realtime envelope");
            }
            return envelope;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Malformed realtime envelope: " + e.getOriginalMessage(), e);
        }
    }

    /** A text field of the payload, or null. */
    public String payloadText(String field) {
        JsonNode node = payload == null ? null : payload.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
