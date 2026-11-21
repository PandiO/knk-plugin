package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.HarvestApi;
import net.knightsandkings.knk.core.production.harvest.HarvestArea;
import net.knightsandkings.knk.core.production.harvest.HarvestCatalog;
import net.knightsandkings.knk.core.production.harvest.HarvestRule;
import net.knightsandkings.knk.core.production.survey.AreaSpec;
import okhttp3.OkHttpClient;

/**
 * Player harvesting (PandiO/knk-workspace#134): the contract of knk-web-api {@code Dtos/ProductionHarvestDtos.cs} (#685),
 * {@code GET api/Harvest/catalog} and {@code POST api/Harvest/reports}. Rules without an id, kind or blueprint and areas
 * without an id or structure are dropped while reading.
 */
public class HarvestApiImpl extends BaseApiImpl implements HarvestApi {

    static final String CATALOG_ENDPOINT = "/Harvest/catalog";
    static final String REPORTS_ENDPOINT = "/Harvest/reports";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RuleDto(@JsonProperty("id") int id, @JsonProperty("name") String name,
                   @JsonProperty("productionKindId") Integer productionKindId, @JsonProperty("enabled") Boolean enabled,
                   @JsonProperty("targetKind") String targetKind, @JsonProperty("targets") List<String> targets,
                   @JsonProperty("minBlockAge") Integer minBlockAge,
                   @JsonProperty("requiredPermission") String requiredPermission,
                   @JsonProperty("minTitleBracketId") Integer minTitleBracketId,
                   @JsonProperty("toolClass") String toolClass, @JsonProperty("minToolTier") String minToolTier,
                   @JsonProperty("cooldownSeconds") Integer cooldownSeconds,
                   @JsonProperty("yieldItemBlueprintId") Integer yieldItemBlueprintId,
                   @JsonProperty("placeholder") String placeholder,
                   @JsonProperty("placeholderMaterial") String placeholderMaterial,
                   @JsonProperty("regenSeconds") Integer regenSeconds) {

        HarvestRule toRule() {
            if (id <= 0 || productionKindId == null || yieldItemBlueprintId == null || Boolean.FALSE.equals(enabled)) {
                return null;
            }
            return new HarvestRule(id, name, productionKindId,
                    HarvestRule.parse(HarvestRule.TargetKind.class, targetKind, HarvestRule.TargetKind.BLOCK), targets,
                    minBlockAge == null ? 0 : minBlockAge, requiredPermission, minTitleBracketId,
                    HarvestRule.parse(HarvestRule.ToolClass.class, toolClass, HarvestRule.ToolClass.ANY),
                    HarvestRule.parse(HarvestRule.ToolTier.class, minToolTier, HarvestRule.ToolTier.ANY),
                    cooldownSeconds == null ? 0 : cooldownSeconds, yieldItemBlueprintId,
                    HarvestRule.parse(HarvestRule.Placeholder.class, placeholder, HarvestRule.Placeholder.NONE),
                    placeholderMaterial, regenSeconds == null ? 0 : regenSeconds);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PointDto(@JsonProperty("x") int x, @JsonProperty("z") int z) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AreaDto(@JsonProperty("productionAreaId") int productionAreaId, @JsonProperty("name") String name,
                   @JsonProperty("productionStructureId") int productionStructureId,
                   @JsonProperty("productionStructureName") String productionStructureName,
                   @JsonProperty("productionKindId") int productionKindId,
                   @JsonProperty("productionEnabled") Boolean productionEnabled,
                   @JsonProperty("worldName") String worldName, @JsonProperty("shape") String shape,
                   @JsonProperty("wgRegionId") String wgRegionId, @JsonProperty("points") List<PointDto> points,
                   @JsonProperty("centerX") Integer centerX, @JsonProperty("centerY") Integer centerY,
                   @JsonProperty("centerZ") Integer centerZ, @JsonProperty("radius") Integer radius,
                   @JsonProperty("minX") Integer minX, @JsonProperty("minY") Integer minY,
                   @JsonProperty("minZ") Integer minZ, @JsonProperty("maxX") Integer maxX,
                   @JsonProperty("maxY") Integer maxY, @JsonProperty("maxZ") Integer maxZ,
                   @JsonProperty("poolFill") Double poolFill) {

        HarvestArea toArea() {
            if (productionAreaId <= 0 || productionStructureId <= 0) {
                return null;
            }
            List<int[]> corners = new ArrayList<>();
            for (PointDto p : points == null ? List.<PointDto>of() : points) {
                if (p != null) {
                    corners.add(new int[] {p.x(), p.z()});
                }
            }
            AreaSpec spec = new AreaSpec(productionAreaId, productionStructureId, productionStructureName, name, shape,
                    false, true, worldName == null || worldName.isBlank() ? null : worldName, wgRegionId, corners,
                    centerX, centerY, centerZ, radius, minX, minY, minZ, maxX, maxY, maxZ);
            return new HarvestArea(spec, productionKindId, productionEnabled == null || productionEnabled,
                    poolFill == null ? 1.0 : Math.max(0, Math.min(1, poolFill)));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CatalogDto(@JsonProperty("featureEnabled") boolean featureEnabled,
                      @JsonProperty("generatedAt") String generatedAt, @JsonProperty("rules") List<RuleDto> rules,
                      @JsonProperty("areas") List<AreaDto> areas) {
    }

    record EntryDto(@JsonProperty("idempotencyKey") String idempotencyKey, @JsonProperty("userId") int userId,
                    @JsonProperty("harvestRuleId") int harvestRuleId,
                    @JsonProperty("productionAreaId") int productionAreaId, @JsonProperty("breaks") int breaks,
                    @JsonProperty("fortuneLevel") int fortuneLevel, @JsonProperty("harvestedAt") String harvestedAt) {
    }

    record ReportDto(@JsonProperty("entries") List<EntryDto> entries) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EntryResultDto(@JsonProperty("idempotencyKey") String idempotencyKey, @JsonProperty("status") String status,
                          @JsonProperty("error") String error, @JsonProperty("itemBlueprintId") Integer itemBlueprintId,
                          @JsonProperty("workerUnits") long workerUnits, @JsonProperty("regenSeconds") int regenSeconds,
                          @JsonProperty("poolAfter") double poolAfter, @JsonProperty("poolCapacity") double poolCapacity) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReportResultDto(@JsonProperty("featureEnabled") boolean featureEnabled,
                           @JsonProperty("entries") List<EntryResultDto> entries) {
    }

    public HarvestApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper, AuthProvider authProvider,
                          ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<HarvestCatalog> catalog() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + CATALOG_ENDPOINT;
            try {
                return toCatalog(parse(get(url), CatalogDto.class, url));
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the harvest catalogue", e);
            }
        }, executor);
    }

    static HarvestCatalog toCatalog(CatalogDto dto) {
        if (dto == null) {
            return HarvestCatalog.EMPTY;
        }
        List<HarvestRule> rules = new ArrayList<>();
        for (RuleDto row : dto.rules() == null ? List.<RuleDto>of() : dto.rules()) {
            HarvestRule rule = row == null ? null : row.toRule();
            if (rule != null) {
                rules.add(rule);
            }
        }
        List<HarvestArea> areas = new ArrayList<>();
        for (AreaDto row : dto.areas() == null ? List.<AreaDto>of() : dto.areas()) {
            HarvestArea area = row == null ? null : row.toArea();
            if (area != null) {
                areas.add(area);
            }
        }
        Instant at = null;
        try {
            at = dto.generatedAt() == null ? null : java.time.OffsetDateTime.parse(dto.generatedAt()).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            try {
                at = Instant.parse(dto.generatedAt() + "Z");
            } catch (java.time.format.DateTimeParseException ignored) {
                // informational only
            }
        }
        return new HarvestCatalog(dto.featureEnabled(), at, rules, areas);
    }

    @Override
    public CompletableFuture<ReportResult> report(List<ReportEntry> entries) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + REPORTS_ENDPOINT;
            try {
                String body = objectMapper.writeValueAsString(new ReportDto(entries.stream()
                        .map(e -> new EntryDto(e.idempotencyKey(), e.userId(), e.harvestRuleId(), e.productionAreaId(),
                                Math.max(1, e.breaks()), Math.max(0, e.fortuneLevel()),
                                e.harvestedAt() == null ? null : e.harvestedAt().toString()))
                        .toList()));
                ReportResultDto result = parse(postJson(url, body, false), ReportResultDto.class, url);
                if (result == null) {
                    return new ReportResult(false, List.of());
                }
                List<EntryResult> rows = new ArrayList<>();
                for (EntryResultDto row : result.entries() == null ? List.<EntryResultDto>of() : result.entries()) {
                    if (row != null && row.idempotencyKey() != null) {
                        rows.add(new EntryResult(row.idempotencyKey(), row.status(), row.error(), row.itemBlueprintId(),
                                Math.max(0, row.workerUnits()), Math.max(0, row.regenSeconds()), row.poolAfter(),
                                row.poolCapacity()));
                    }
                }
                return new ReportResult(result.featureEnabled(), rows);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the harvest report", e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to send the harvest report", e);
            }
        }, executor);
    }
}
