package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.PagedQueryDto;
import net.knightsandkings.knk.api.dto.PagedResultDto;
import net.knightsandkings.knk.core.domain.common.Page;
import net.knightsandkings.knk.core.domain.common.PagedQuery;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.StructureMenusApi;
import net.knightsandkings.knk.core.production.menu.StructureMenu;
import net.knightsandkings.knk.core.production.menu.StructureRow;
import net.knightsandkings.knk.core.production.menu.TransportDryRun;
import net.knightsandkings.knk.core.production.menu.TransportOrderRow;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * The in-game structure menus (PandiO/knk-workspace#74): the contract of knk-web-api P6a (#683, comment on #74),
 * {@code api/StructureMenus}, plus the staff buttons' routes {@code api/TransportOrders/{id}/cancel|retry} and {@code GET
 * api/ProductionStructures/{id}/transport}. A 404 of a structure is an empty answer, not a logged error.
 */
public class StructureMenusApiImpl extends BaseApiImpl implements StructureMenusApi {

    static final String MENUS = "/StructureMenus";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RowDto(@JsonProperty("id") Integer id, @JsonProperty("structureType") String structureType,
                  @JsonProperty("name") String name, @JsonProperty("productionKindId") Integer productionKindId,
                  @JsonProperty("kindName") String kindName, @JsonProperty("townId") Integer townId,
                  @JsonProperty("townName") String townName, @JsonProperty("isRural") Boolean isRural,
                  @JsonProperty("level") Integer level, @JsonProperty("products") List<String> products,
                  @JsonProperty("status") String status, @JsonProperty("statusText") String statusText) {

        StructureRow toRow() {
            if (id == null) {
                return null;
            }
            return new StructureRow(id, structureType, name, productionKindId, kindName, townId, townName,
                    Boolean.TRUE.equals(isRural), level, products, status, statusText);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MenuDto(@JsonProperty("id") Integer id, @JsonProperty("structureType") String structureType,
                   @JsonProperty("name") String name, @JsonProperty("productionKindId") Integer productionKindId,
                   @JsonProperty("kindName") String kindName, @JsonProperty("townId") Integer townId,
                   @JsonProperty("townName") String townName, @JsonProperty("isRural") Boolean isRural,
                   @JsonProperty("level") Integer level, @JsonProperty("products") List<String> products,
                   @JsonProperty("status") String status, @JsonProperty("statusText") String statusText,
                   @JsonProperty("viewerSeesDetails") Boolean viewerSeesDetails,
                   @JsonProperty("viewerIsStaff") Boolean viewerIsStaff, @JsonProperty("detail") DetailDto detail) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DetailDto(@JsonProperty("workersPresent") Integer workersPresent,
                     @JsonProperty("workersTotal") Integer workersTotal, @JsonProperty("workersAway") Integer workersAway,
                     @JsonProperty("rates") List<RateDto> rates, @JsonProperty("storages") List<StorageDto> storages,
                     @JsonProperty("warnings") List<String> warnings,
                     @JsonProperty("notProducingReasons") List<String> notProducingReasons,
                     @JsonProperty("openOrderCount") Integer openOrderCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RateDto(@JsonProperty("productionLineId") Integer productionLineId,
                   @JsonProperty("itemBlueprintId") Integer itemBlueprintId,
                   @JsonProperty("itemBlueprintName") String itemBlueprintName,
                   @JsonProperty("ratePerHour") Double ratePerHour, @JsonProperty("status") String status,
                   @JsonProperty("notProducingReasons") List<String> notProducingReasons) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StorageDto(@JsonProperty("storageId") Integer storageId, @JsonProperty("name") String name,
                      @JsonProperty("purpose") String purpose, @JsonProperty("totalUnits") Long totalUnits,
                      @JsonProperty("maxUnits") Long maxUnits, @JsonProperty("lines") List<LineDto> lines) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LineDto(@JsonProperty("itemBlueprintId") Integer itemBlueprintId,
                   @JsonProperty("itemBlueprintName") String itemBlueprintName, @JsonProperty("amount") Long amount,
                   @JsonProperty("itemInstanceId") Long itemInstanceId,
                   @JsonProperty("customDisplayName") String customDisplayName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderDto(@JsonProperty("id") Integer id, @JsonProperty("origin") String origin,
                    @JsonProperty("status") String status, @JsonProperty("statusReason") String statusReason,
                    @JsonProperty("sourceStructureName") String sourceStructureName,
                    @JsonProperty("sourceStorageName") String sourceStorageName,
                    @JsonProperty("destinations") String destinations, @JsonProperty("unitsRequested") Long unitsRequested,
                    @JsonProperty("unitsLoaded") Long unitsLoaded, @JsonProperty("unitsDelivered") Long unitsDelivered,
                    @JsonProperty("unitsReturned") Long unitsReturned, @JsonProperty("createdAt") String createdAt,
                    @JsonProperty("etaAt") String etaAt, @JsonProperty("completedAt") String completedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlanDto(@JsonProperty("valid") Boolean valid, @JsonProperty("errors") List<String> errors,
                   @JsonProperty("carrierCapacity") Long carrierCapacity, @JsonProperty("lines") List<PlanLineDto> lines) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PlanLineDto(@JsonProperty("itemBlueprintName") String itemBlueprintName,
                       @JsonProperty("requested") Long requested) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DryRunDto(@JsonProperty("productionStructureId") Integer productionStructureId,
                     @JsonProperty("featureEnabled") Boolean featureEnabled,
                     @JsonProperty("automaticTransport") Boolean automaticTransport,
                     @JsonProperty("roadConnection") String roadConnection, @JsonProperty("outputUnits") Double outputUnits,
                     @JsonProperty("outputMaxUnits") Long outputMaxUnits, @JsonProperty("threshold") Double threshold,
                     @JsonProperty("carrierCapacity") Long carrierCapacity,
                     @JsonProperty("workersPresent") Integer workersPresent,
                     @JsonProperty("workersTotal") Integer workersTotal, @JsonProperty("warnings") List<String> warnings,
                     @JsonProperty("triggerReasons") List<String> triggerReasons,
                     @JsonProperty("heldReasons") List<String> heldReasons, @JsonProperty("wouldCreate") PlanDto wouldCreate,
                     @JsonProperty("openOrders") List<OrderDto> openOrders) {
    }

    public StructureMenusApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                 AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<Page<StructureRow>> search(PagedQuery query, Integer actingUserId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + MENUS + "/search";
            int number = query == null ? 1 : Math.max(1, query.pageNumber());
            int size = query == null ? 28 : Math.max(1, Math.min(200, query.pageSize()));
            try {
                String json = objectMapper.writeValueAsString(new PagedQueryDto(number, size,
                        query == null ? null : query.searchTerm(), null, false,
                        query == null || query.filters() == null ? Map.of() : query.filters()));
                Request request = ProductionHttp.jsonPost(ProductionHttp.acting(newRequest(url), actingUserId), json).build();
                PagedResultDto<RowDto> page = parse(execute(request, url), new TypeReference<PagedResultDto<RowDto>>() { }, url);
                List<StructureRow> rows = new ArrayList<>();
                for (RowDto dto : page == null || page.items() == null ? List.<RowDto>of() : page.items()) {
                    StructureRow row = dto == null ? null : dto.toRow();
                    if (row != null) {
                        rows.add(row);
                    }
                }
                int total = page == null || page.totalCount() == null ? rows.size() : page.totalCount();
                return new Page<>(rows, total, number, size);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the structure search", e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to search the structures", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<StructureMenu>> get(int structureId, Integer actingUserId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + MENUS + "/" + structureId;
            try {
                ProductionHttp.Reply reply = ProductionHttp.quiet(httpClient,
                        ProductionHttp.acting(newRequest(url), actingUserId).get().build());
                if (reply.code() == 404) {
                    return Optional.<StructureMenu>empty();
                }
                if (!reply.ok()) {
                    throw new CompletionException(new ApiException(url, reply.code(), "Request failed", snippet(reply.body())));
                }
                return Optional.ofNullable(toMenu(parse(reply.body(), MenuDto.class, url)));
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the menu of structure #" + structureId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Page<TransportOrderRow>> orders(int structureId, int page, int pageSize, Integer actingUserId) {
        return CompletableFuture.supplyAsync(() -> {
            int number = Math.max(1, page);
            int size = Math.max(1, Math.min(200, pageSize));
            String url = baseUrl + MENUS + "/" + structureId + "/orders?page=" + number + "&pageSize=" + size;
            try {
                ProductionHttp.Reply reply = ProductionHttp.quiet(httpClient,
                        ProductionHttp.acting(newRequest(url), actingUserId).get().build());
                if (!reply.ok()) {
                    throw new CompletionException(new ApiException(url, reply.code(), "Request failed", snippet(reply.body())));
                }
                PagedResultDto<OrderDto> result = parse(reply.body(), new TypeReference<PagedResultDto<OrderDto>>() { }, url);
                List<TransportOrderRow> rows = new ArrayList<>();
                for (OrderDto dto : result == null || result.items() == null ? List.<OrderDto>of() : result.items()) {
                    TransportOrderRow row = toOrder(dto);
                    if (row != null) {
                        rows.add(row);
                    }
                }
                int total = result == null || result.totalCount() == null ? rows.size() : result.totalCount();
                return new Page<>(rows, total, number, size);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the orders of structure #" + structureId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> cancelOrder(int orderId, int actingUserId) {
        return orderAction(orderId, "cancel", actingUserId, "{\"reason\":\"Cancelled in game\"}");
    }

    @Override
    public CompletableFuture<Void> retryOrder(int orderId, int actingUserId) {
        return orderAction(orderId, "retry", actingUserId, "{}");
    }

    private CompletableFuture<Void> orderAction(int orderId, String verb, int actingUserId, String json) {
        return CompletableFuture.runAsync(() -> {
            String url = baseUrl + "/TransportOrders/" + orderId + "/" + verb;
            try {
                execute(ProductionHttp.jsonPost(ProductionHttp.acting(newRequest(url), actingUserId), json).build(), url);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to " + verb + " transport #" + orderId, e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<TransportDryRun>> transportDryRun(int structureId, Integer actingUserId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/ProductionStructures/" + structureId + "/transport";
            try {
                ProductionHttp.Reply reply = ProductionHttp.quiet(httpClient,
                        ProductionHttp.acting(newRequest(url), actingUserId).get().build());
                if (reply.code() == 404) {
                    return Optional.<TransportDryRun>empty();
                }
                if (!reply.ok()) {
                    throw new CompletionException(new ApiException(url, reply.code(), "Request failed", snippet(reply.body())));
                }
                return Optional.of(toDryRun(parse(reply.body(), DryRunDto.class, url), structureId));
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the transport dry run of structure #" + structureId, e);
            }
        }, executor);
    }

    static StructureMenu toMenu(MenuDto dto) {
        if (dto == null || dto.id() == null) {
            return null;
        }
        StructureRow row = new StructureRow(dto.id(), dto.structureType(), dto.name(), dto.productionKindId(),
                dto.kindName(), dto.townId(), dto.townName(), Boolean.TRUE.equals(dto.isRural()), dto.level(),
                dto.products(), dto.status(), dto.statusText());
        StructureMenu.Detail detail = null;
        DetailDto d = dto.detail();
        if (d != null) {
            List<StructureMenu.Rate> rates = new ArrayList<>();
            for (RateDto r : d.rates() == null ? List.<RateDto>of() : d.rates()) {
                if (r != null) {
                    rates.add(new StructureMenu.Rate(r.productionLineId() != null ? r.productionLineId() : 0,
                            r.itemBlueprintId() != null ? r.itemBlueprintId() : 0, r.itemBlueprintName(),
                            r.ratePerHour() != null ? r.ratePerHour() : 0d, r.status(), r.notProducingReasons()));
                }
            }
            List<StructureMenu.StorageView> storages = new ArrayList<>();
            for (StorageDto s : d.storages() == null ? List.<StorageDto>of() : d.storages()) {
                if (s == null || s.storageId() == null) {
                    continue;
                }
                List<StructureMenu.Line> lines = new ArrayList<>();
                for (LineDto l : s.lines() == null ? List.<LineDto>of() : s.lines()) {
                    if (l != null && l.itemBlueprintId() != null) {
                        lines.add(new StructureMenu.Line(l.itemBlueprintId(), l.itemBlueprintName(),
                                l.amount() != null ? l.amount() : 0L, l.itemInstanceId(), l.customDisplayName()));
                    }
                }
                storages.add(new StructureMenu.StorageView(s.storageId(), s.name(), s.purpose(),
                        s.totalUnits() != null ? s.totalUnits() : 0L, s.maxUnits(), lines));
            }
            detail = new StructureMenu.Detail(d.workersPresent(), d.workersTotal(), d.workersAway(), rates, storages,
                    d.warnings(), d.notProducingReasons(), d.openOrderCount() != null ? d.openOrderCount() : 0);
        }
        return new StructureMenu(row, Boolean.TRUE.equals(dto.viewerSeesDetails()), Boolean.TRUE.equals(dto.viewerIsStaff()),
                detail);
    }

    static TransportOrderRow toOrder(OrderDto dto) {
        if (dto == null || dto.id() == null) {
            return null;
        }
        return new TransportOrderRow(dto.id(), dto.origin(), dto.status(), dto.statusReason(), dto.sourceStructureName(),
                dto.sourceStorageName(), dto.destinations(), n(dto.unitsRequested()), n(dto.unitsLoaded()),
                n(dto.unitsDelivered()), n(dto.unitsReturned()), instant(dto.createdAt()), instant(dto.etaAt()),
                instant(dto.completedAt()));
    }

    static TransportDryRun toDryRun(DryRunDto dto, int structureId) {
        String wouldCreate = null;
        PlanDto plan = dto.wouldCreate();
        if (plan != null) {
            List<String> parts = new ArrayList<>();
            for (PlanLineDto line : plan.lines() == null ? List.<PlanLineDto>of() : plan.lines()) {
                if (line != null) {
                    parts.add(n(line.requested()) + " " + (line.itemBlueprintName() != null ? line.itemBlueprintName() : "items"));
                }
            }
            wouldCreate = parts.isEmpty() ? "an order" : String.join(", ", parts);
            if (Boolean.FALSE.equals(plan.valid()) && plan.errors() != null && !plan.errors().isEmpty()) {
                wouldCreate += " (not valid: " + String.join("; ", plan.errors()) + ")";
            }
        }
        return new TransportDryRun(dto.productionStructureId() != null ? dto.productionStructureId() : structureId,
                Boolean.TRUE.equals(dto.featureEnabled()), Boolean.TRUE.equals(dto.automaticTransport()),
                dto.roadConnection(), dto.outputUnits() != null ? dto.outputUnits() : 0d, dto.outputMaxUnits(),
                dto.threshold() != null ? dto.threshold() : 0d, n(dto.carrierCapacity()),
                dto.workersPresent() != null ? dto.workersPresent() : 0, dto.workersTotal() != null ? dto.workersTotal() : 0,
                dto.warnings(), dto.triggerReasons(), dto.heldReasons(), wouldCreate,
                dto.openOrders() == null ? 0 : dto.openOrders().size());
    }

    private static long n(Long value) {
        return value == null ? 0L : value;
    }

    static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ignored) {
                try {
                    return Instant.parse(value + "Z");
                } catch (DateTimeParseException alsoIgnored) {
                    return null;
                }
            }
        }
    }
}
