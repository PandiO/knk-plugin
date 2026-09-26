package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.PrivateMessageLogBatchResultDto;
import net.knightsandkings.knk.api.dto.PrivateMessageLogEntryDto;
import net.knightsandkings.knk.core.domain.messaging.PrivateMessageLogEntry;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.PrivateMessageLogApi;
import okhttp3.OkHttpClient;

/**
 * knk-web-api's PrivateMessageLogController ({@code POST api/private-message-log/batch}; KNG-18
 * Phase 3). Needs the plugin's service key (api.auth.type: apikey) - anything else gets 401.
 */
public class PrivateMessageLogApiImpl extends BaseApiImpl implements PrivateMessageLogApi {
    // baseUrl is expected to already include /api
    private static final String ENDPOINT = "/private-message-log/batch";

    public PrivateMessageLogApiImpl(
        String baseUrl,
        OkHttpClient httpClient,
        ObjectMapper objectMapper,
        AuthProvider authProvider,
        ExecutorService executor,
        boolean debugLogging
    ) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<BatchResult> submitBatch(List<PrivateMessageLogEntry> entries) {
        List<PrivateMessageLogEntryDto> body = entries.stream().map(PrivateMessageLogApiImpl::toDto).toList();
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT;
            try {
                // Never log the body: it is players' private messages, and the server log has no
                // retention (api.debug-logging would otherwise copy every PM into latest.log).
                PrivateMessageLogBatchResultDto result = parse(postJson(url, objectMapper.writeValueAsString(body), false),
                    PrivateMessageLogBatchResultDto.class, url);
                return new BatchResult(result.accepted(), result.duplicates());
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to send " + entries.size() + " private message log entries", e);
            }
        }, executor);
    }

    static PrivateMessageLogEntryDto toDto(PrivateMessageLogEntry entry) {
        return new PrivateMessageLogEntryDto(
            entry.clientMessageId().toString(),
            entry.sentAt().toString(),
            uuid(entry.senderUuid()),
            entry.senderName(),
            uuid(entry.recipientUuid()),
            entry.recipientName(),
            entry.content(),
            outcome(entry.outcome()),
            entry.viaReply());
    }

    /** The API's PrivateMessageOutcome names. */
    static String outcome(PrivateMessageLogEntry.Outcome outcome) {
        return switch (outcome) {
            case DELIVERED -> "Delivered";
            case BLOCKED_IGNORED -> "BlockedIgnored";
            case BLOCKED_RATE_LIMITED -> "BlockedRateLimited";
            case BLOCKED_FROZEN -> "BlockedFrozen";
        };
    }

    private static String uuid(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }
}
