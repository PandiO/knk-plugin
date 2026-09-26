package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.TitleBracketDto;
import net.knightsandkings.knk.api.mapper.TitleBracketsMapper;
import net.knightsandkings.knk.core.domain.users.TitleBracket;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.TitleBracketsQueryApi;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/** {@code GET /api/title-brackets} (InventoryMenu content port CP3). */
public class TitleBracketsQueryApiImpl extends BaseApiImpl implements TitleBracketsQueryApi {

    static final String ENDPOINT = "/title-brackets";

    public TitleBracketsQueryApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                     AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<List<TitleBracket>> listAll() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT;
            try {
                List<TitleBracketDto> dtos = parse(get(url), new TypeReference<List<TitleBracketDto>>() {}, url);
                return TitleBracketsMapper.toCore(dtos);
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to list title brackets", e);
            }
        }, executor);
    }
}
