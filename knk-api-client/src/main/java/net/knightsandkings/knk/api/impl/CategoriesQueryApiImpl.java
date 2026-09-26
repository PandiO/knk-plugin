package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.CategoryListItemDto;
import net.knightsandkings.knk.core.domain.item.KnkItemCategory;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.CategoriesQueryApi;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/** {@code GET /api/Categories} (menu follow-up 2026-09-26). */
public class CategoriesQueryApiImpl extends BaseApiImpl implements CategoriesQueryApi {

    public CategoriesQueryApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                  AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<List<KnkItemCategory>> listAll() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/Categories";
            try {
                return toCore(parse(get(url), new TypeReference<List<CategoryListItemDto>>() {}, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to list categories", e);
            }
        }, executor);
    }

    static List<KnkItemCategory> toCore(List<CategoryListItemDto> dtos) {
        return dtos == null ? List.of() : dtos.stream()
                .filter(Objects::nonNull)
                .filter(d -> d.id() != null && d.name() != null && !d.name().isBlank())
                .map(d -> new KnkItemCategory(d.id(), d.name().trim(), d.parentCategoryId()))
                .toList();
    }
}
