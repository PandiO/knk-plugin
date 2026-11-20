package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.SiegeShopApi;
import net.knightsandkings.knk.core.siege.shop.ShopCatalogue;
import net.knightsandkings.knk.core.siege.shop.ShopCategory;
import net.knightsandkings.knk.core.siege.shop.ShopCurve;
import net.knightsandkings.knk.core.siege.shop.ShopEntry;
import net.knightsandkings.knk.core.siege.shop.ShopEntryKind;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Siege shop reads (PandiO/knk-workspace#868) over knk-web-api's {@code GET /api/SiegeShop/catalogue} (L2, #867) and
 * {@code GET /api/UserSiegeWeaponUnlocks/by-user/{userId}} (weapons phase 1, #782), both readable with the plugin key.
 * Entries of a kind the plugin doesn't know (a later {@code MagicAction}) are left out.
 */
public class SiegeShopApiImpl extends BaseApiImpl implements SiegeShopApi {

    private static final String CATALOGUE = "/SiegeShop/catalogue";
    private static final String UNLOCKS = "/UserSiegeWeaponUnlocks/by-user/";

    public SiegeShopApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper, AuthProvider authProvider,
                            ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    /** The wire shape of {@code SiegeShopCatalogueDto}. */
    record CatalogueDto(
            @JsonProperty("gameMode") String gameMode,
            @JsonProperty("siegeScenarioId") Integer siegeScenarioId,
            @JsonProperty("categories") List<CategoryDto> categories) {
    }

    record CategoryDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("key") String key,
            @JsonProperty("name") String name,
            @JsonProperty("iconMaterial") String iconMaterial,
            @JsonProperty("sortOrder") Integer sortOrder,
            @JsonProperty("entries") List<EntryDto> entries) {
    }

    record EntryDto(
            @JsonProperty("id") Integer id,
            @JsonProperty("categoryId") Integer categoryId,
            @JsonProperty("kind") String kind,
            @JsonProperty("source") String source,
            @JsonProperty("itemBlueprintId") Integer itemBlueprintId,
            @JsonProperty("itemBlueprintName") String itemBlueprintName,
            @JsonProperty("quantity") Integer quantity,
            @JsonProperty("siegeWeaponTemplateId") Integer siegeWeaponTemplateId,
            @JsonProperty("siegeWeaponTemplateName") String siegeWeaponTemplateName,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("iconMaterial") String iconMaterial,
            @JsonProperty("price") Long price,
            @JsonProperty("priceStep") Long priceStep,
            @JsonProperty("priceCurve") String priceCurve,
            @JsonProperty("priceGrowthFactor") Double priceGrowthFactor,
            @JsonProperty("priceMax") Long priceMax,
            @JsonProperty("cooldownSeconds") Double cooldownSeconds,
            @JsonProperty("cooldownStepSeconds") Double cooldownStepSeconds,
            @JsonProperty("cooldownCurve") String cooldownCurve,
            @JsonProperty("cooldownGrowthFactor") Double cooldownGrowthFactor,
            @JsonProperty("cooldownMaxSeconds") Double cooldownMaxSeconds,
            @JsonProperty("maxPerMatch") Integer maxPerMatch,
            @JsonProperty("sortOrder") Integer sortOrder) {
    }

    record UnlockDto(@JsonProperty("siegeWeaponTemplateId") Integer siegeWeaponTemplateId) {
    }

    @Override
    public CompletableFuture<ShopCatalogue> catalogue(String gameMode, Integer scenarioId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + CATALOGUE + "?mode=" + URLEncoder.encode(gameMode == null ? "Siege" : gameMode, StandardCharsets.UTF_8)
                    + (scenarioId == null ? "" : "&scenarioId=" + scenarioId);
            try {
                if (debugLogging) LOGGER.info("API Request: GET " + url);
                CatalogueDto dto = parse(execute(newRequest(url).get().build(), url), new TypeReference<CatalogueDto>() { }, url);
                return toCore(dto, gameMode, scenarioId);
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to load the siege shop catalogue (" + gameMode + ", scenario " + scenarioId + ")", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Set<Integer>> unlockedWeaponTemplates(int userId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + UNLOCKS + userId;
            try {
                if (debugLogging) LOGGER.info("API Request: GET " + url);
                List<UnlockDto> rows = parse(execute(newRequest(url).get().build(), url), new TypeReference<List<UnlockDto>>() { }, url);
                Set<Integer> ids = new LinkedHashSet<>();
                if (rows != null) rows.forEach(r -> {
                    if (r != null && r.siegeWeaponTemplateId() != null) ids.add(r.siegeWeaponTemplateId());
                });
                return Set.copyOf(ids);
            } catch (ApiException e) {
                if (e.getStatusCode() == 404) return Set.<Integer>of();
                throw new RuntimeException("Failed to load the siege weapon unlocks of user " + userId, e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load the siege weapon unlocks of user " + userId, e);
            }
        }, executor);
    }

    static ShopCatalogue toCore(CatalogueDto dto, String gameMode, Integer scenarioId) {
        if (dto == null) return new ShopCatalogue(gameMode, scenarioId, List.of());
        List<ShopCategory> categories = new ArrayList<>();
        if (dto.categories() != null) {
            for (CategoryDto c : dto.categories()) {
                if (c == null || c.id() == null) continue;
                List<ShopEntry> entries = new ArrayList<>();
                if (c.entries() != null) {
                    for (EntryDto e : c.entries()) {
                        toCore(e, c.id()).ifPresent(entries::add);
                    }
                }
                categories.add(new ShopCategory(c.id(), c.key(), c.name(), c.iconMaterial(),
                        c.sortOrder() == null ? 0 : c.sortOrder(), entries));
            }
        }
        return new ShopCatalogue(dto.gameMode() == null ? gameMode : dto.gameMode(),
                dto.siegeScenarioId() == null ? scenarioId : dto.siegeScenarioId(), categories);
    }

    static Optional<ShopEntry> toCore(EntryDto e, int categoryId) {
        if (e == null || e.id() == null) return Optional.empty();
        Optional<ShopEntryKind> kind = ShopEntryKind.fromApi(e.kind());
        if (kind.isEmpty()) return Optional.empty();
        if (kind.get() == ShopEntryKind.ITEM && e.itemBlueprintId() == null) return Optional.empty();
        return Optional.of(new ShopEntry(
                e.id(),
                e.categoryId() == null ? categoryId : e.categoryId(),
                kind.get(),
                e.source(),
                e.itemBlueprintId(),
                e.itemBlueprintName(),
                e.quantity() == null ? 1 : e.quantity(),
                e.siegeWeaponTemplateId(),
                e.siegeWeaponTemplateName(),
                e.displayName(),
                e.iconMaterial(),
                e.price() == null ? 0 : e.price(),
                e.priceStep(),
                ShopCurve.fromApi(e.priceCurve()),
                e.priceGrowthFactor(),
                e.priceMax(),
                e.cooldownSeconds(),
                e.cooldownStepSeconds(),
                ShopCurve.fromApi(e.cooldownCurve()),
                e.cooldownGrowthFactor(),
                e.cooldownMaxSeconds(),
                e.maxPerMatch(),
                e.sortOrder() == null ? 0 : e.sortOrder()));
    }
}
