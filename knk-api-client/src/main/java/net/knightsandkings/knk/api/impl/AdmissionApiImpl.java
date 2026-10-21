package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.AdmissionDtos.AdmissionCheckDto;
import net.knightsandkings.knk.core.domain.admission.AdmissionResult;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.AdmissionApi;
import okhttp3.OkHttpClient;

/** {@code GET api/Admission/{uuid}?name=} (invitations plan §6.2). Bodies carry claim codes, so are never logged. */
public class AdmissionApiImpl extends BaseApiImpl implements AdmissionApi {
    // baseUrl is expected to already include /api
    private static final String ENDPOINT = "/Admission";

    public AdmissionApiImpl(
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
    public CompletableFuture<AdmissionResult> check(UUID uuid, String name) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/" + uuid + "?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
            try {
                AdmissionCheckDto dto = parse(get(url), AdmissionCheckDto.class, url);
                return new AdmissionResult(dto.admitted(), dto.reason(), dto.code(), dto.expiresAt());
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to check admission for " + uuid, e);
            }
        }, executor);
    }
}
