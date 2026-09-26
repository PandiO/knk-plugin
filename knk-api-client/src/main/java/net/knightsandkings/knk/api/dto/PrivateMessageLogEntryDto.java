package net.knightsandkings.knk.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps to CreatePrivateMessageLogEntryDto in knk-web-api - one element of
 * POST /api/private-message-log/batch. {@code sentAt} is an ISO-8601 instant, the UUIDs are null
 * for the console, {@code outcome} is the API's PrivateMessageOutcome name (e.g. "BlockedIgnored").
 */
public record PrivateMessageLogEntryDto(
    @JsonProperty("clientMessageId") String clientMessageId,
    @JsonProperty("sentAt") String sentAt,
    @JsonProperty("senderUuid") String senderUuid,
    @JsonProperty("senderName") String senderName,
    @JsonProperty("recipientUuid") String recipientUuid,
    @JsonProperty("recipientName") String recipientName,
    @JsonProperty("content") String content,
    @JsonProperty("outcome") String outcome,
    @JsonProperty("viaReply") boolean viaReply
) {}
