package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.PagedQueryDto;
import net.knightsandkings.knk.api.dto.PagedResultDto;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.ShopDisplaySpotsApi;
import net.knightsandkings.knk.core.production.shop.display.DisplaySpot;
import okhttp3.OkHttpClient;

/**
 * Display spots and best sellers (display stalls P12, PandiO/knk-workspace#250): knk-web-api {@code api/ShopDisplaySpots}
 * and {@code api/ShopTradeStats/search}, with the plugin key.
 */
public class ShopDisplaySpotsApiImpl extends BaseApiImpl implements ShopDisplaySpotsApi {

    static final String ENDPOINT = "/ShopDisplaySpots";
    static final String STATS_ENDPOINT = "/ShopTradeStats/search";
    /** The API caps search pages at 200. */
    static final int PAGE_SIZE = 200;
    /** Safety stops: 10,000 spots, 5,000 hourly stat rows per shop. */
    static final int LIST_MAX_PAGES = 50;
    static final int STATS_MAX_PAGES = 25;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SpotDto(@JsonProperty("id") Integer id, @JsonProperty("shopId") Integer shopId,
                   @JsonProperty("worldName") String worldName, @JsonProperty("x") Integer x, @JsonProperty("y") Integer y,
                   @JsonProperty("z") Integer z, @JsonProperty("facing") String facing,
                   @JsonProperty("pinnedCatalogueEntryId") Integer pinnedCatalogueEntryId,
                   @JsonProperty("sortOrder") Integer sortOrder, @JsonProperty("label") String label,
                   @JsonProperty("enabled") Boolean enabled) {

        DisplaySpot toSpot() {
            if (id == null || shopId == null || worldName == null || x == null || y == null || z == null) {
                return null;
            }
            return new DisplaySpot(id, shopId, worldName, x, y, z, DisplaySpot.Facing.parse(facing), pinnedCatalogueEntryId,
                    sortOrder == null ? 0 : sortOrder, label, enabled == null || enabled);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CreateDto(@JsonProperty("shopId") int shopId, @JsonProperty("worldName") String worldName,
                     @JsonProperty("x") int x, @JsonProperty("y") int y, @JsonProperty("z") int z,
                     @JsonProperty("facing") String facing, @JsonProperty("label") String label) {
    }

    record PinDto(@JsonProperty("pinnedCatalogueEntryId") int pinnedCatalogueEntryId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StatDto(@JsonProperty("itemBlueprintId") Integer itemBlueprintId, @JsonProperty("unitsSold") Long unitsSold) {
    }

    public ShopDisplaySpotsApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                   AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<List<DisplaySpot>> list(Integer shopId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/search";
            Map<String, String> filters = new LinkedHashMap<>();
            if (shopId != null) {
                filters.put("shopId", String.valueOf(shopId));
            }
            List<DisplaySpot> all = new ArrayList<>();
            try {
                for (int page = 1; page <= LIST_MAX_PAGES; page++) {
                    String body = objectMapper.writeValueAsString(new PagedQueryDto(page, PAGE_SIZE, null, null, false, filters));
                    PagedResultDto<SpotDto> result = parse(postJson(url, body), new TypeReference<PagedResultDto<SpotDto>>() { }, url);
                    List<SpotDto> items = result == null || result.items() == null ? List.of() : result.items();
                    for (SpotDto item : items) {
                        DisplaySpot spot = item == null ? null : item.toSpot();
                        if (spot != null) {
                            all.add(spot);
                        }
                    }
                    int total = result == null || result.totalCount() == null ? all.size() : result.totalCount();
                    if (items.isEmpty() || page * PAGE_SIZE >= total) {
                        break;
                    }
                }
                return all;
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the display spot search", e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to list the display spots", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<DisplaySpot> create(SpotDraft draft) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT;
            try {
                String body = objectMapper.writeValueAsString(new CreateDto(draft.shopId(), draft.world(), draft.x(), draft.y(),
                        draft.z(), draft.facing() == null ? null : draft.facing().apiName(),
                        draft.label() == null || draft.label().isBlank() ? null : draft.label().trim()));
                return spot(postJson(url, body), url);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the display spot", e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to register the display spot", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<DisplaySpot> pin(int spotId, Integer catalogueEntryId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/" + spotId;
            try {
                String body = objectMapper.writeValueAsString(new PinDto(catalogueEntryId == null ? 0 : catalogueEntryId));
                return spot(putJson(url, body), url);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the pin", e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to pin display spot #" + spotId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> delete(int spotId) {
        return CompletableFuture.runAsync(() -> {
            String url = baseUrl + ENDPOINT + "/" + spotId;
            try {
                delete(url);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to remove display spot #" + spotId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Map<Integer, Long>> unitsSold(int shopId, Instant since) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + STATS_ENDPOINT;
            Map<String, String> filters = new LinkedHashMap<>();
            filters.put("shopId", String.valueOf(shopId));
            if (since != null) {
                filters.put("from", since.truncatedTo(ChronoUnit.HOURS).toString());
            }
            Map<Integer, Long> sold = new HashMap<>();
            try {
                int seen = 0;
                for (int page = 1; page <= STATS_MAX_PAGES; page++) {
                    String body = objectMapper.writeValueAsString(new PagedQueryDto(page, PAGE_SIZE, null, null, false, filters));
                    PagedResultDto<StatDto> result = parse(postJson(url, body), new TypeReference<PagedResultDto<StatDto>>() { }, url);
                    List<StatDto> items = result == null || result.items() == null ? List.of() : result.items();
                    for (StatDto item : items) {
                        if (item != null && item.itemBlueprintId() != null && item.unitsSold() != null && item.unitsSold() > 0) {
                            sold.merge(item.itemBlueprintId(), item.unitsSold(), Long::sum);
                        }
                    }
                    seen += items.size();
                    int total = result == null || result.totalCount() == null ? seen : result.totalCount();
                    if (items.isEmpty() || seen >= total) {
                        break;
                    }
                }
                return sold;
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the trade stats search", e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the trade stats of shop #" + shopId, e);
            }
        }, executor);
    }

    private DisplaySpot spot(String json, String url) throws ApiException {
        SpotDto dto = parse(json, SpotDto.class, url);
        DisplaySpot spot = dto == null ? null : dto.toSpot();
        if (spot == null) {
            throw new ApiException("The API's answer about the display spot is missing fields");
        }
        return spot;
    }
}
