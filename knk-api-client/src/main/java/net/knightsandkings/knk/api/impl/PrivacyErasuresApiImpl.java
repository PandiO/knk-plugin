package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.PrivacyErasureDtos;
import net.knightsandkings.knk.api.realtime.PrivacyEvents;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.PrivacyErasuresApi;
import net.knightsandkings.knk.core.privacy.ErasedAccount;
import net.knightsandkings.knk.core.privacy.ErasureFeedPage;
import okhttp3.OkHttpClient;

/** knk-web-api's PrivacyErasuresController (realtime S5, PandiO/knk-workspace#184): the erasure feed. Plugin key only. */
public class PrivacyErasuresApiImpl extends BaseApiImpl implements PrivacyErasuresApi {
    // baseUrl is expected to already include /api
    static final String ENDPOINT = "/privacy/erasures";

    public PrivacyErasuresApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                  AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<ErasureFeedPage> since(long sinceId, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "?since=" + Math.max(0, sinceId) + "&limit=" + Math.max(1, limit);
            try {
                return map(parse(get(url), PrivacyErasureDtos.Feed.class, url), sinceId);
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to read the erasure feed", e);
            }
        }, executor);
    }

    static ErasureFeedPage map(PrivacyErasureDtos.Feed dto, long sinceId) {
        if (dto == null) {
            return new ErasureFeedPage(List.of(), sinceId, false, 0);
        }
        List<ErasedAccount> items = new ArrayList<>();
        if (dto.items() != null) {
            for (PrivacyErasureDtos.Item item : dto.items()) {
                if (item != null && item.erasureId() > 0) {
                    items.add(new ErasedAccount(item.erasureId(), item.userId(), PrivacyEvents.uuidOrNull(item.formerUuid())));
                }
            }
        }
        return new ErasureFeedPage(items, dto.lastId(), dto.hasMore(), dto.latestId());
    }
}
