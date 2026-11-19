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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.ProductionSurveyApi;
import net.knightsandkings.knk.core.production.survey.AreaSpec;
import net.knightsandkings.knk.core.production.survey.SurveyDue;
import okhttp3.OkHttpClient;

/**
 * Yield-area surveys (PandiO/knk-workspace#679): the contract of knk-web-api {@code Dtos/ProductionYieldDtos.cs}
 * (#138): {@code GET api/ProductionAreas/survey-due}, {@code POST api/ProductionAreas/{id}/surveys},
 * {@code POST api/ProductionStructures/{id}/survey} and {@code GET api/ProductionAreas}. Areas without an id or structure
 * are dropped while reading.
 */
public class ProductionSurveyApiImpl extends BaseApiImpl implements ProductionSurveyApi {

    static final String AREAS_ENDPOINT = "/ProductionAreas";
    static final String DUE_ENDPOINT = "/ProductionAreas/survey-due";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PointDto(@JsonProperty("x") int x, @JsonProperty("z") int z) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NavDto(@JsonProperty("id") int id, @JsonProperty("name") String name) {
    }

    /** {@code ProductionAreaDto} and {@code ProductionAreaOverlapDto} (the overlap has the area id as productionAreaId). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AreaDto(@JsonProperty("id") Integer id, @JsonProperty("productionAreaId") Integer productionAreaId,
                   @JsonProperty("productionStructureId") Integer productionStructureId,
                   @JsonProperty("productionStructure") NavDto productionStructure,
                   @JsonProperty("productionStructureName") String productionStructureName,
                   @JsonProperty("name") String name, @JsonProperty("shape") String shape,
                   @JsonProperty("exclusive") Boolean exclusive, @JsonProperty("enabled") Boolean enabled,
                   @JsonProperty("worldName") String worldName, @JsonProperty("wgRegionId") String wgRegionId,
                   @JsonProperty("points") List<PointDto> points, @JsonProperty("centerX") Integer centerX,
                   @JsonProperty("centerY") Integer centerY, @JsonProperty("centerZ") Integer centerZ,
                   @JsonProperty("radius") Integer radius, @JsonProperty("minX") Integer minX,
                   @JsonProperty("minY") Integer minY, @JsonProperty("minZ") Integer minZ,
                   @JsonProperty("maxX") Integer maxX, @JsonProperty("maxY") Integer maxY,
                   @JsonProperty("maxZ") Integer maxZ) {

        /** @param world the world of the surveyed area for an overlap (the overlap DTO has none) */
        AreaSpec toSpec(String world) {
            Integer areaId = id != null && id > 0 ? id : productionAreaId;
            if (areaId == null || areaId <= 0 || productionStructureId == null || productionStructureId <= 0) {
                return null;
            }
            List<int[]> corners = new ArrayList<>();
            for (PointDto p : points == null ? List.<PointDto>of() : points) {
                if (p != null) {
                    corners.add(new int[] {p.x(), p.z()});
                }
            }
            String structureName = productionStructure != null ? productionStructure.name() : productionStructureName;
            return new AreaSpec(areaId, productionStructureId, structureName, name, shape, Boolean.TRUE.equals(exclusive),
                    enabled == null || enabled, worldName == null || worldName.isBlank() ? world : worldName, wgRegionId,
                    corners, centerX, centerY, centerZ, radius, minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DueDto(@JsonProperty("area") AreaDto area, @JsonProperty("productionKindId") Integer productionKindId,
                  @JsonProperty("productionKindName") String productionKindName,
                  @JsonProperty("surveyProfileJson") String surveyProfileJson,
                  @JsonProperty("surveyProfileVersion") int surveyProfileVersion, @JsonProperty("trigger") String trigger,
                  @JsonProperty("overlappingSharedAreas") List<AreaDto> overlappingSharedAreas) {
    }

    record UnitDto(@JsonProperty("unitKey") String unitKey, @JsonProperty("count") long count,
                   @JsonProperty("sharedBy") int sharedBy, @JsonProperty("extra") String extra) {
    }

    record ReportDto(@JsonProperty("surveyedAt") String surveyedAt, @JsonProperty("profileVersion") int profileVersion,
                     @JsonProperty("trigger") String trigger, @JsonProperty("contentHash") String contentHash,
                     @JsonProperty("note") String note, @JsonProperty("units") List<UnitDto> units) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MaterializationDto(@JsonProperty("linesCreated") List<Integer> linesCreated,
                              @JsonProperty("linesUpdated") List<Integer> linesUpdated) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SurveyDto(@JsonProperty("id") long id, @JsonProperty("totalUnits") long totalUnits,
                     @JsonProperty("materialization") MaterializationDto materialization) {
    }

    record RequestDto(@JsonProperty("productionAreaId") Integer productionAreaId) {
    }

    public ProductionSurveyApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                   AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<List<SurveyDue>> surveyDue(int limit) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + DUE_ENDPOINT + "?limit=" + Math.max(1, limit);
            try {
                List<DueDto> rows = parse(get(url), new TypeReference<List<DueDto>>() { }, url);
                List<SurveyDue> due = new ArrayList<>();
                for (DueDto row : rows == null ? List.<DueDto>of() : rows) {
                    AreaSpec area = row == null || row.area() == null ? null : row.area().toSpec(null);
                    if (area == null) {
                        continue;
                    }
                    List<AreaSpec> overlaps = new ArrayList<>();
                    for (AreaDto overlap : row.overlappingSharedAreas() == null ? List.<AreaDto>of() : row.overlappingSharedAreas()) {
                        AreaSpec spec = overlap == null ? null : overlap.toSpec(area.worldName());
                        if (spec != null) {
                            overlaps.add(spec);
                        }
                    }
                    due.add(new SurveyDue(area, row.productionKindId(), row.productionKindName(), row.surveyProfileJson(),
                            row.surveyProfileVersion(), row.trigger(), overlaps));
                }
                return due;
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the survey queue", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<ReportResult> report(int areaId, Report report) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + AREAS_ENDPOINT + "/" + areaId + "/surveys";
            try {
                String body = objectMapper.writeValueAsString(toDto(report));
                SurveyDto survey = parse(postJson(url, body, false), SurveyDto.class, url);
                if (survey == null) {
                    return new ReportResult(0, 0, 0, 0);
                }
                MaterializationDto m = survey.materialization();
                return new ReportResult(survey.id(), survey.totalUnits(),
                        m == null || m.linesCreated() == null ? 0 : m.linesCreated().size(),
                        m == null || m.linesUpdated() == null ? 0 : m.linesUpdated().size());
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the survey report", e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to send the survey report", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<AreaSpec>> requestSurvey(int structureId, Integer areaId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + "/ProductionStructures/" + structureId + "/survey";
            try {
                String body = objectMapper.writeValueAsString(new RequestDto(areaId));
                return specs(parse(postJson(url, body), new TypeReference<List<AreaDto>>() { }, url));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the survey request", e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to request a survey", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<AreaSpec>> areas() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + AREAS_ENDPOINT;
            try {
                return specs(parse(get(url), new TypeReference<List<AreaDto>>() { }, url));
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the production areas", e);
            }
        }, executor);
    }

    private static List<AreaSpec> specs(List<AreaDto> rows) {
        List<AreaSpec> specs = new ArrayList<>();
        for (AreaDto row : rows == null ? List.<AreaDto>of() : rows) {
            AreaSpec spec = row == null ? null : row.toSpec(null);
            if (spec != null) {
                specs.add(spec);
            }
        }
        return specs;
    }

    static ReportDto toDto(Report report) {
        Instant at = report.surveyedAt() == null ? Instant.now() : report.surveyedAt();
        return new ReportDto(at.toString(), report.profileVersion(), report.trigger(), report.contentHash(), report.note(),
                report.units().stream().map(u -> new UnitDto(u.unitKey(), u.count(), u.sharedBy(), u.extra())).toList());
    }
}
