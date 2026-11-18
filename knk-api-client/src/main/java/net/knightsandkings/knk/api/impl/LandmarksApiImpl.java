package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.LandmarkDto;
import net.knightsandkings.knk.api.mapper.LandmarkMapper;
import net.knightsandkings.knk.core.domain.landmarks.KnkLandmark;
import net.knightsandkings.knk.core.domain.landmarks.LandmarkWrite;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.LandmarksApi;
import okhttp3.OkHttpClient;

/**
 * {@code api/landmarks} (off-road navigation phase 5.1, PandiO/knk-workspace#708). A refused call completes
 * exceptionally with the {@link ApiException} itself (its status and the API's reason), so the command can show it.
 */
public class LandmarksApiImpl extends BaseApiImpl implements LandmarksApi {

    static final String ENDPOINT = "/landmarks";

    public LandmarksApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper, AuthProvider authProvider,
                            ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<List<KnkLandmark>> list(String world) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT
                + (world == null || world.isBlank() ? "" : "?world=" + URLEncoder.encode(world, StandardCharsets.UTF_8));
            return call(() -> LandmarkMapper.toCore(parse(get(url), new TypeReference<List<LandmarkDto>>() { }, url)));
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<KnkLandmark>> get(int id) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/" + id;
            try {
                return Optional.ofNullable(LandmarkMapper.toCore(parse(get(url), LandmarkDto.class, url)));
            } catch (ApiException e) {
                if (e.getStatusCode() == 404) {
                    return Optional.<KnkLandmark>empty();
                }
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<KnkLandmark> create(LandmarkWrite landmark) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT;
            return call(() -> LandmarkMapper.toCore(parse(postJson(url, json(landmark)), LandmarkDto.class, url)));
        }, executor);
    }

    @Override
    public CompletableFuture<KnkLandmark> update(int id, LandmarkWrite landmark) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/" + id;
            return call(() -> LandmarkMapper.toCore(parse(putJson(url, json(landmark)), LandmarkDto.class, url)));
        }, executor);
    }

    @Override
    public CompletableFuture<Void> delete(int id) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/" + id;
            call(() -> delete(url));
            return null;
        }, executor);
    }

    private String json(LandmarkWrite landmark) {
        try {
            return objectMapper.writeValueAsString(LandmarkMapper.toWrite(landmark));
        } catch (JsonProcessingException e) {
            throw new CompletionException(e);
        }
    }

    private interface Call<T> {
        T run() throws ApiException, IOException;
    }

    private static <T> T call(Call<T> call) {
        try {
            return call.run();
        } catch (ApiException | IOException e) {
            throw new CompletionException(e);
        }
    }
}
