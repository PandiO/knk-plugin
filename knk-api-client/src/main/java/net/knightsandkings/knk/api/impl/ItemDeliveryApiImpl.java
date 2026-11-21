package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.ItemDeliveryDtos;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.ItemDeliveryApi;
import net.knightsandkings.knk.core.production.inbox.ClaimResult;
import net.knightsandkings.knk.core.production.inbox.DeliveryRequest;
import net.knightsandkings.knk.core.production.inbox.DeliveryResult;
import net.knightsandkings.knk.core.production.inbox.InboxNotice;
import net.knightsandkings.knk.core.production.inbox.InboxView;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * Item delivery and the reward inbox (PandiO/knk-workspace#684): the contract of knk-web-api P7 (#60, comment on #684),
 * {@code api/ItemDeliveries} and {@code api/Inbox}. A claim acts for the player ({@code X-Acting-User-Id}).
 */
public class ItemDeliveryApiImpl extends BaseApiImpl implements ItemDeliveryApi {

    static final String DELIVERIES = "/ItemDeliveries";
    static final String INBOX = "/Inbox";

    public ItemDeliveryApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                               AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<DeliveryResult> deliver(DeliveryRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + DELIVERIES;
            try {
                String json = objectMapper.writeValueAsString(new ItemDeliveryDtos.DeliveryRequestDto(request.userId(),
                        request.reason() != null ? request.reason().apiName() : DeliveryRequest.Reason.OTHER.apiName(),
                        request.sourceType(), request.sourceRef(), request.note(), request.idempotencyKey(),
                        lines(request.lines())));
                return toResult(parse(postJson(url, json), ItemDeliveryDtos.DeliveryDto.class, url));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the item delivery", e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to deliver items to user " + request.userId(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<InboxView> inbox(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + INBOX + "/" + userId;
            try {
                return toView(parse(get(url), ItemDeliveryDtos.ViewDto.class, url), userId);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the inbox of user " + userId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<ClaimResult> claim(int userId, String idempotencyKey, List<DeliveryRequest.Line> claimLines) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + INBOX + "/" + userId + "/claim";
            try {
                String json = objectMapper.writeValueAsString(new ItemDeliveryDtos.MoveDto(idempotencyKey, lines(claimLines)));
                Request request = ProductionHttp.jsonPost(ProductionHttp.acting(newRequest(url), userId), json).build();
                if (debugLogging) {
                    LOGGER.info("API Request: POST " + url + " " + json);
                }
                return toClaim(parse(execute(request, url), ItemDeliveryDtos.MoveResultDto.class, url), userId);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the inbox claim", e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to claim from the inbox of user " + userId, e);
            }
        }, executor);
    }

    static List<ItemDeliveryDtos.LineRequestDto> lines(List<DeliveryRequest.Line> lines) {
        List<ItemDeliveryDtos.LineRequestDto> out = new ArrayList<>();
        for (DeliveryRequest.Line line : lines == null ? List.<DeliveryRequest.Line>of() : lines) {
            out.add(line.itemInstanceId() != null
                    ? new ItemDeliveryDtos.LineRequestDto(null, line.itemInstanceId(), 1)
                    : new ItemDeliveryDtos.LineRequestDto(line.itemBlueprintId(), null, line.amount()));
        }
        return out;
    }

    static DeliveryResult toResult(ItemDeliveryDtos.DeliveryDto dto) {
        return new DeliveryResult(dto.id() != null ? dto.id() : 0L, dto.status(), orZero(dto.inboxedUnits()),
                orZero(dto.lostUnits()), orZero(dto.capUnits()), instant(dto.expiresAt()), Boolean.TRUE.equals(dto.replayed()));
    }

    static InboxView toView(ItemDeliveryDtos.ViewDto dto, int userId) {
        if (dto == null) {
            return InboxView.empty(userId);
        }
        List<InboxView.Entry> entries = new ArrayList<>();
        for (ItemDeliveryDtos.EntryDto e : dto.entries() == null ? List.<ItemDeliveryDtos.EntryDto>of() : dto.entries()) {
            if (e != null && e.itemBlueprintId() != null && e.amount() != null && e.amount() > 0) {
                entries.add(new InboxView.Entry(e.itemBlueprintId(), e.itemBlueprintName(), e.itemInstanceId(),
                        e.customDisplayName(), e.amount(), instant(e.nextExpiresAt()), orZero(e.nextExpiringAmount())));
            }
        }
        long used = dto.usedUnits() == null ? 0L : (long) Math.ceil(dto.usedUnits());
        return new InboxView(dto.userId() != null ? dto.userId() : userId, dto.storageId(), used, orZero(dto.capUnits()),
                dto.fillPercent() != null ? dto.fillPercent() : 0, dto.warningPercent(), dto.premiumTierGroupId(),
                Boolean.TRUE.equals(dto.isLocked()), entries);
    }

    static ClaimResult toClaim(ItemDeliveryDtos.MoveResultDto dto, int userId) {
        ItemDeliveryDtos.StockResultDto result = dto.result();
        List<String> reasons = new ArrayList<>();
        if (result != null && result.lines() != null) {
            for (ItemDeliveryDtos.LineResultDto line : result.lines()) {
                String reason = line == null ? null : line.rejectReason();
                if (reason != null && !reason.isBlank() && !"None".equalsIgnoreCase(reason)) {
                    reasons.add(reason);
                }
            }
        }
        return new ClaimResult(result != null ? result.status() : null, result != null && Boolean.TRUE.equals(result.replayed()),
                result != null ? orZero(result.accepted()) : 0L, reasons, toView(dto.view(), userId));
    }

    /** The core notice of an InboxUpdated payload; null without one. */
    public static InboxNotice toNotice(ItemDeliveryDtos.InboxNotificationDto dto) {
        if (dto == null) {
            return null;
        }
        return new InboxNotice(dto.event(), dto.deliveryId(), dto.reason(), orZero(dto.inboxedUnits()), orZero(dto.lostUnits()),
                dto.warningPercent(), dto.fillPercent() != null ? dto.fillPercent() : 0, orZero(dto.capUnits()),
                orZero(dto.expiringUnits()), instant(dto.expiresAt()));
    }

    /** An API date: with a zone or offset as given, without one as UTC (the API stores UTC); null when unreadable. */
    static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (RuntimeException ignored) {
            // no offset
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static long orZero(Long value) {
        return value != null ? value : 0L;
    }
}
