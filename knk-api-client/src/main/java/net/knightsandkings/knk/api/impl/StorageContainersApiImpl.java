package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import net.knightsandkings.knk.core.ports.api.StorageContainersApi;
import net.knightsandkings.knk.core.production.terminal.BindRequest;
import net.knightsandkings.knk.core.production.terminal.DisplaySample;
import net.knightsandkings.knk.core.production.terminal.StockMoveResult;
import net.knightsandkings.knk.core.production.terminal.StorageContainerInfo;
import net.knightsandkings.knk.core.production.terminal.TerminalPage;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * Storage terminals and displays (PandiO/knk-workspace#74): the contract of knk-web-api P6a (#683, comment on #74),
 * {@code api/StorageContainers}. A by-block lookup of an unbound block (404) is not an error and is not logged as one.
 */
public class StorageContainersApiImpl extends BaseApiImpl implements StorageContainersApi {

    static final String ENDPOINT = "/StorageContainers";
    /** Page size of {@link #list}; the API caps search pages at 200. */
    static final int LIST_PAGE_SIZE = 200;
    /** Safety stop of {@link #list}. */
    static final int LIST_MAX_PAGES = 50;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContainerDto(@JsonProperty("id") Integer id, @JsonProperty("storageId") Integer storageId,
                        @JsonProperty("storageName") String storageName, @JsonProperty("storage") NavDto storage,
                        @JsonProperty("mode") String mode, @JsonProperty("containerType") String containerType,
                        @JsonProperty("isDouble") Boolean isDouble, @JsonProperty("slots") Integer slots,
                        @JsonProperty("worldName") String worldName, @JsonProperty("x") Integer x,
                        @JsonProperty("y") Integer y, @JsonProperty("z") Integer z, @JsonProperty("label") String label) {

        StorageContainerInfo toInfo() {
            StorageContainerInfo.Mode parsed = StorageContainerInfo.Mode.parse(mode);
            if (id == null || storageId == null || parsed == null || worldName == null || x == null || y == null || z == null) {
                return null;
            }
            boolean dbl = Boolean.TRUE.equals(isDouble);
            String name = storageName != null ? storageName : storage != null ? storage.name() : null;
            return new StorageContainerInfo(id, storageId, name, parsed, containerType, dbl,
                    slots != null ? slots : dbl ? 54 : 27, worldName, x, y, z, label);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NavDto(@JsonProperty("id") Integer id, @JsonProperty("name") String name) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BindDto(@JsonProperty("storageId") int storageId, @JsonProperty("mode") String mode,
                   @JsonProperty("containerType") String containerType, @JsonProperty("isDouble") boolean isDouble,
                   @JsonProperty("worldName") String worldName, @JsonProperty("x") int x, @JsonProperty("y") int y,
                   @JsonProperty("z") int z, @JsonProperty("label") String label) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EntryDto(@JsonProperty("itemBlueprintId") Integer itemBlueprintId,
                    @JsonProperty("itemBlueprintName") String itemBlueprintName, @JsonProperty("amount") Long amount,
                    @JsonProperty("itemInstanceId") Long itemInstanceId,
                    @JsonProperty("customDisplayName") String customDisplayName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TerminalDto(@JsonProperty("containerId") Integer containerId, @JsonProperty("storageId") Integer storageId,
                       @JsonProperty("storageName") String storageName, @JsonProperty("totalUnits") Long totalUnits,
                       @JsonProperty("maxUnits") Long maxUnits, @JsonProperty("isLocked") Boolean isLocked,
                       @JsonProperty("page") Integer page, @JsonProperty("pageSize") Integer pageSize,
                       @JsonProperty("totalCount") Integer totalCount, @JsonProperty("entries") List<EntryDto> entries) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record MoveDto(@JsonProperty("itemBlueprintId") Integer itemBlueprintId,
                   @JsonProperty("itemInstanceId") Long itemInstanceId, @JsonProperty("amount") long amount,
                   @JsonProperty("idempotencyKey") String idempotencyKey) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LineResultDto(@JsonProperty("accepted") Long accepted, @JsonProperty("requested") Long requested,
                         @JsonProperty("rejectReason") String rejectReason, @JsonProperty("reason") String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResultDto(@JsonProperty("status") String status, @JsonProperty("replayed") Boolean replayed,
                     @JsonProperty("accepted") Long accepted, @JsonProperty("lines") List<LineResultDto> lines) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SampleItemDto(@JsonProperty("itemBlueprintId") Integer itemBlueprintId,
                         @JsonProperty("itemBlueprintName") String itemBlueprintName, @JsonProperty("stacks") Integer stacks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SampleDto(@JsonProperty("containerId") Integer containerId, @JsonProperty("storageId") Integer storageId,
                     @JsonProperty("slots") Integer slots, @JsonProperty("fillRatio") Double fillRatio,
                     @JsonProperty("filledSlots") Integer filledSlots, @JsonProperty("items") List<SampleItemDto> items) {
    }

    public StorageContainersApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                    AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<Optional<StorageContainerInfo>> byBlock(String world, int x, int y, int z) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/by-block?world=" + URLEncoder.encode(world, StandardCharsets.UTF_8)
                    + "&x=" + x + "&y=" + y + "&z=" + z;
            try {
                ProductionHttp.Reply reply = ProductionHttp.quiet(httpClient, newRequest(url).get().build());
                if (reply.code() == 404) {
                    return Optional.<StorageContainerInfo>empty();
                }
                if (!reply.ok()) {
                    throw new CompletionException(new ApiException(url, reply.code(), "Request failed", snippet(reply.body())));
                }
                return Optional.ofNullable(parse(reply.body(), ContainerDto.class, url).toInfo());
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to look up the container at " + world + " " + x + " " + y + " " + z, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<StorageContainerInfo>> list(StorageContainerInfo.Mode mode, String worldName) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/search";
            Map<String, String> filters = new LinkedHashMap<>();
            if (mode != null) {
                filters.put("mode", mode.apiName());
            }
            if (worldName != null) {
                filters.put("worldName", worldName);
            }
            List<StorageContainerInfo> all = new ArrayList<>();
            try {
                for (int page = 1; page <= LIST_MAX_PAGES; page++) {
                    String body = objectMapper.writeValueAsString(
                            new PagedQueryDto(page, LIST_PAGE_SIZE, null, null, false, filters));
                    PagedResultDto<ContainerDto> result = parse(postJson(url, body),
                            new TypeReference<PagedResultDto<ContainerDto>>() { }, url);
                    List<ContainerDto> items = result == null || result.items() == null ? List.of() : result.items();
                    for (ContainerDto item : items) {
                        StorageContainerInfo info = item == null ? null : item.toInfo();
                        if (info != null) {
                            all.add(info);
                        }
                    }
                    int total = result == null || result.totalCount() == null ? all.size() : result.totalCount();
                    if (items.isEmpty() || page * LIST_PAGE_SIZE >= total) {
                        break;
                    }
                }
                return all;
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the container search", e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to list the bound containers", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<StorageContainerInfo> bind(BindRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT;
            try {
                String body = objectMapper.writeValueAsString(new BindDto(request.storageId(), request.mode().apiName(),
                        request.containerType(), request.isDouble(), request.worldName(), request.x(), request.y(),
                        request.z(), request.label()));
                StorageContainerInfo info = parse(postJson(url, body), ContainerDto.class, url).toInfo();
                if (info == null) {
                    throw new ApiException("The API's answer to the bind is missing fields");
                }
                return info;
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the bind request", e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to bind the container", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> unbind(int containerId) {
        return CompletableFuture.runAsync(() -> {
            String url = baseUrl + ENDPOINT + "/" + containerId;
            try {
                delete(url);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to unbind container #" + containerId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<TerminalPage> terminal(int containerId, int page, int pageSize, Integer actingUserId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/" + containerId + "/terminal?page=" + Math.max(1, page)
                    + "&pageSize=" + Math.max(1, Math.min(54, pageSize));
            try {
                Request request = ProductionHttp.acting(newRequest(url), actingUserId).get().build();
                return toPage(parse(execute(request, url), TerminalDto.class, url), containerId);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read terminal #" + containerId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<StockMoveResult> put(int containerId, int actingUserId, Integer itemBlueprintId,
                                                  Long itemInstanceId, long amount, String idempotencyKey) {
        return move(containerId, "put", actingUserId, itemBlueprintId, itemInstanceId, amount, idempotencyKey);
    }

    @Override
    public CompletableFuture<StockMoveResult> take(int containerId, int actingUserId, Integer itemBlueprintId,
                                                   Long itemInstanceId, long amount, String idempotencyKey) {
        return move(containerId, "take", actingUserId, itemBlueprintId, itemInstanceId, amount, idempotencyKey);
    }

    private CompletableFuture<StockMoveResult> move(int containerId, String verb, int actingUserId, Integer itemBlueprintId,
                                                    Long itemInstanceId, long amount, String idempotencyKey) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/" + containerId + "/" + verb;
            try {
                String json = objectMapper.writeValueAsString(new MoveDto(itemBlueprintId, itemInstanceId, amount,
                        idempotencyKey));
                Request request = ProductionHttp.jsonPost(ProductionHttp.acting(newRequest(url), actingUserId), json).build();
                if (debugLogging) {
                    LOGGER.info("API Request: POST " + url + " " + json);
                }
                return toResult(parse(execute(request, url), ResultDto.class, url));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the " + verb, e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to " + verb + " through terminal #" + containerId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<DisplaySample> display(int containerId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/" + containerId + "/display";
            try {
                SampleDto dto = parse(get(url), SampleDto.class, url);
                List<DisplaySample.Item> items = new ArrayList<>();
                for (SampleItemDto item : dto.items() == null ? List.<SampleItemDto>of() : dto.items()) {
                    if (item != null && item.itemBlueprintId() != null && item.stacks() != null && item.stacks() > 0) {
                        items.add(new DisplaySample.Item(item.itemBlueprintId(), item.itemBlueprintName(), item.stacks()));
                    }
                }
                return new DisplaySample(dto.containerId() != null ? dto.containerId() : containerId,
                        dto.storageId() != null ? dto.storageId() : 0, dto.slots() != null ? dto.slots() : 0,
                        dto.fillRatio() != null ? dto.fillRatio() : 0d, dto.filledSlots() != null ? dto.filledSlots() : 0,
                        items);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read display #" + containerId, e);
            }
        }, executor);
    }

    static TerminalPage toPage(TerminalDto dto, int containerId) {
        List<TerminalPage.Entry> entries = new ArrayList<>();
        for (EntryDto e : dto.entries() == null ? List.<EntryDto>of() : dto.entries()) {
            if (e != null && e.itemBlueprintId() != null && e.amount() != null && e.amount() > 0) {
                entries.add(new TerminalPage.Entry(e.itemBlueprintId(), e.itemBlueprintName(), e.amount(),
                        e.itemInstanceId(), e.customDisplayName()));
            }
        }
        return new TerminalPage(dto.containerId() != null ? dto.containerId() : containerId,
                dto.storageId() != null ? dto.storageId() : 0, dto.storageName(),
                dto.totalUnits() != null ? dto.totalUnits() : 0L, dto.maxUnits(), Boolean.TRUE.equals(dto.isLocked()),
                dto.page() != null ? dto.page() : 1, dto.pageSize() != null ? dto.pageSize() : entries.size(),
                dto.totalCount() != null ? dto.totalCount() : entries.size(), entries);
    }

    static StockMoveResult toResult(ResultDto dto) {
        List<String> reasons = new ArrayList<>();
        for (LineResultDto line : dto.lines() == null ? List.<LineResultDto>of() : dto.lines()) {
            String reason = line == null ? null : line.rejectReason() != null ? line.rejectReason() : line.reason();
            if (reason != null && !reason.isBlank() && !"None".equalsIgnoreCase(reason)) {
                reasons.add(reason);
            }
        }
        return new StockMoveResult(dto.status(), Boolean.TRUE.equals(dto.replayed()),
                dto.accepted() != null ? dto.accepted() : 0L, reasons);
    }
}
