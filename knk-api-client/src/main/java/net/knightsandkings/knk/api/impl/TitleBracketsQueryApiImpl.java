package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.TitleBracketDto;
import net.knightsandkings.knk.core.domain.users.KnkTitleBracket;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.TitleBracketsQueryApi;
import okhttp3.OkHttpClient;

/** Siege Phase 5: GET /api/TitleBrackets (read-only, lowest title first). */
public class TitleBracketsQueryApiImpl extends BaseApiImpl implements TitleBracketsQueryApi {

    public TitleBracketsQueryApiImpl(
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
    public CompletableFuture<List<KnkTitleBracket>> getAll() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/TitleBrackets";
            try {
                List<TitleBracketDto> dtos = parse(get(url), new TypeReference<List<TitleBracketDto>>() {}, url);
                return dtos.stream()
                    .filter(Objects::nonNull)
                    .map(d -> new KnkTitleBracket(d.id(), d.name(), d.minExperience()))
                    .toList();
            } catch (IOException e) {
                throw new ApiException(url, "IO error fetching title brackets", e);
            }
        }, executor);
    }
}
