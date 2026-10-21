package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.ArenaDtos.ArenaDto;
import net.knightsandkings.knk.api.mapper.ArenaMapper;
import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.ArenasApi;
import okhttp3.OkHttpClient;

/** Arena duels (KNG-210): {@code GET /api/Arenas}. */
public class ArenasApiImpl extends BaseApiImpl implements ArenasApi {

    static final String ENDPOINT = "/Arenas";

    public ArenasApiImpl(
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
    public CompletableFuture<List<KnkArena>> getAll() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT;
            try {
                return ArenaMapper.toCore(parse(get(url), new TypeReference<List<ArenaDto>>() {}, url));
            } catch (IOException e) {
                throw new ApiException(url, "IO error fetching arenas", e);
            }
        }, executor);
    }
}
