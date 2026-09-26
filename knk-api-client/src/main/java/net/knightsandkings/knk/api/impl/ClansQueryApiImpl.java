package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.ClanDtos.BannerDesignDto;
import net.knightsandkings.knk.api.dto.ClanDtos.ClanDto;
import net.knightsandkings.knk.api.mapper.ClanMapper;
import net.knightsandkings.knk.core.domain.clan.KnkBannerDesign;
import net.knightsandkings.knk.core.domain.clan.KnkClan;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.ClansQueryApi;
import okhttp3.OkHttpClient;

/** Siege Phase 1: GET /api/Clans..., /api/BannerDesigns/{id}. A 404 completes with null. */
public class ClansQueryApiImpl extends BaseApiImpl implements ClansQueryApi {

    public ClansQueryApiImpl(
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
    public CompletableFuture<List<KnkClan>> listClans() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/Clans";
            try {
                List<ClanDto> dtos = parse(get(url), new TypeReference<List<ClanDto>>() {}, url);
                return dtos.stream().filter(Objects::nonNull).map(ClanMapper::toCore).toList();
            } catch (IOException e) {
                throw new ApiException(url, "IO error listing clans", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<KnkClan> getClanById(int id) {
        return CompletableFuture.supplyAsync(
            () -> ClanMapper.toCore(getOrNull(baseUrl + "/Clans/" + id, ClanDto.class)), executor);
    }

    @Override
    public CompletableFuture<KnkClan> getDefaultClanForTown(int townId) {
        return CompletableFuture.supplyAsync(
            () -> ClanMapper.toCore(getOrNull(baseUrl + "/Clans/default-for-town/" + townId, ClanDto.class)), executor);
    }

    @Override
    public CompletableFuture<KnkBannerDesign> getBannerDesignById(int id) {
        return CompletableFuture.supplyAsync(
            () -> ClanMapper.toCore(getOrNull(baseUrl + "/BannerDesigns/" + id, BannerDesignDto.class)), executor);
    }

    private <T> T getOrNull(String url, Class<T> type) {
        try {
            return parse(get(url), type, url);
        } catch (ApiException e) {
            if (e.getStatusCode() == 404) return null;
            throw e;
        } catch (IOException e) {
            throw new ApiException(url, "IO error during GET", e);
        }
    }
}
