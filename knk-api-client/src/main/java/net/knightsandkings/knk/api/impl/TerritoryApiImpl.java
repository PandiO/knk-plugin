package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.TerritoryApi;
import net.knightsandkings.knk.core.territory.TerritoryHolder;
import net.knightsandkings.knk.core.territory.TerritoryInputs;
import net.knightsandkings.knk.core.territory.TerritoryMap;
import net.knightsandkings.knk.core.territory.TerritoryReport;
import okhttp3.OkHttpClient;

/**
 * Territory V1 (PandiO/knk-workspace#689): {@code GET api/Territory/inputs} and {@code PUT api/Territory/report}, the
 * contract of knk-web-api {@code Dtos/TerritoryDtos.cs} (#326). Rows the plugin can't use (no holder, an unknown holder
 * kind, a broken chunk pair) are dropped while reading.
 */
public class TerritoryApiImpl extends BaseApiImpl implements TerritoryApi {

    static final String INPUTS_ENDPOINT = "/Territory/inputs";
    static final String REPORT_ENDPOINT = "/Territory/report";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HolderDto(@JsonProperty("kind") String kind, @JsonProperty("id") int id) {
        TerritoryHolder toHolder() {
            TerritoryHolder.Kind parsed = TerritoryHolder.Kind.parse(kind);
            return parsed == null || id <= 0 ? null : new TerritoryHolder(parsed, id);
        }

        static HolderDto of(TerritoryHolder holder) {
            return holder == null ? null : new HolderDto(holder.kind().apiName(), holder.id());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record NamedDto(@JsonProperty("id") int id, @JsonProperty("name") String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RegionDto(@JsonProperty("id") int id, @JsonProperty("name") String name, @JsonProperty("world") String world,
                     @JsonProperty("wgRegionId") String wgRegionId, @JsonProperty("subRegionIds") List<String> subRegionIds,
                     @JsonProperty("holder") HolderDto holder, @JsonProperty("isCapital") boolean isCapital,
                     @JsonProperty("range") double range) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AwardDto(@JsonProperty("conflictId") int conflictId, @JsonProperty("world") String world,
                    @JsonProperty("holder") HolderDto holder, @JsonProperty("chunks") List<int[]> chunks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InputsDto(@JsonProperty("productionEnabled") boolean productionEnabled, @JsonProperty("version") String version,
                     @JsonProperty("kingdoms") List<NamedDto> kingdoms, @JsonProperty("towns") List<NamedDto> towns,
                     @JsonProperty("districts") List<RegionDto> districts, @JsonProperty("crownLands") List<RegionDto> crownLands,
                     @JsonProperty("awards") List<AwardDto> awards) {
    }

    record OverlapDto(@JsonProperty("holderA") HolderDto holderA, @JsonProperty("holderB") HolderDto holderB,
                      @JsonProperty("chunks") List<int[]> chunks) {
    }

    record StructureDto(@JsonProperty("structureId") int structureId, @JsonProperty("flag") String flag,
                        @JsonProperty("holderA") HolderDto holderA, @JsonProperty("holderB") HolderDto holderB) {
    }

    record ReportDto(@JsonProperty("world") String world, @JsonProperty("inputsVersion") String inputsVersion,
                     @JsonProperty("overlaps") List<OverlapDto> overlaps, @JsonProperty("structures") List<StructureDto> structures) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResultDto(@JsonProperty("world") String world, @JsonProperty("conflictsCreated") int conflictsCreated,
                     @JsonProperty("conflictsUpdated") int conflictsUpdated,
                     @JsonProperty("conflictsNoLongerOverlapping") int conflictsNoLongerOverlapping,
                     @JsonProperty("structuresFlagged") int structuresFlagged,
                     @JsonProperty("structuresCleared") int structuresCleared,
                     @JsonProperty("skipped") List<String> skipped) {
    }

    public TerritoryApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper, AuthProvider authProvider,
                            ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<TerritoryInputs> fetchInputs() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + INPUTS_ENDPOINT;
            try {
                InputsDto dto = parse(get(url), InputsDto.class, url);
                return dto == null ? TerritoryInputs.EMPTY : toInputs(dto);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read the territory inputs", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<TerritoryReport.Result> report(TerritoryReport report) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + REPORT_ENDPOINT;
            try {
                String body = objectMapper.writeValueAsString(toDto(report));
                ResultDto result = parse(putJson(url, body, false), ResultDto.class, url);
                if (result == null) {
                    return new TerritoryReport.Result(report.world(), 0, 0, 0, 0, 0, List.of());
                }
                return new TerritoryReport.Result(result.world() != null ? result.world() : report.world(),
                        result.conflictsCreated(), result.conflictsUpdated(), result.conflictsNoLongerOverlapping(),
                        result.structuresFlagged(), result.structuresCleared(), result.skipped());
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to write the territory report", e);
            } catch (ApiException e) {
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to send the territory report", e);
            }
        }, executor);
    }

    static TerritoryInputs toInputs(InputsDto dto) {
        Map<Integer, String> towns = names(dto.towns());
        Map<Integer, String> kingdoms = names(dto.kingdoms());
        List<TerritoryInputs.Source> sources = new ArrayList<>();
        addSources(sources, dto.districts(), TerritoryInputs.SourceType.DISTRICT);
        addSources(sources, dto.crownLands(), TerritoryInputs.SourceType.CROWN_LAND);
        List<TerritoryInputs.Award> awards = new ArrayList<>();
        for (AwardDto award : dto.awards() == null ? List.<AwardDto>of() : dto.awards()) {
            TerritoryHolder holder = award == null || award.holder() == null ? null : award.holder().toHolder();
            if (holder == null || award.world() == null || award.world().isBlank()) {
                continue;
            }
            Set<Long> chunks = new HashSet<>();
            for (int[] chunk : award.chunks() == null ? List.<int[]>of() : award.chunks()) {
                if (chunk != null && chunk.length == 2) {
                    chunks.add(TerritoryMap.chunkKey(chunk[0], chunk[1]));
                }
            }
            awards.add(new TerritoryInputs.Award(award.conflictId(), award.world(), holder, chunks));
        }
        return new TerritoryInputs(dto.productionEnabled(), dto.version(), towns, kingdoms, sources, awards);
    }

    private static void addSources(List<TerritoryInputs.Source> sources, List<RegionDto> regions, TerritoryInputs.SourceType type) {
        for (RegionDto region : regions == null ? List.<RegionDto>of() : regions) {
            TerritoryHolder holder = region == null || region.holder() == null ? null : region.holder().toHolder();
            if (holder == null || region.wgRegionId() == null || region.wgRegionId().isBlank()) {
                continue;
            }
            String world = region.world() == null || region.world().isBlank() ? null : region.world();
            sources.add(new TerritoryInputs.Source(type, region.id(), region.name(), world, region.wgRegionId().trim(),
                    region.subRegionIds() == null ? List.of()
                            : region.subRegionIds().stream().filter(id -> id != null && !id.isBlank()).map(String::trim).toList(),
                    holder, region.isCapital(), Math.max(0, region.range())));
        }
    }

    private static Map<Integer, String> names(List<NamedDto> rows) {
        Map<Integer, String> names = new HashMap<>();
        for (NamedDto row : rows == null ? List.<NamedDto>of() : rows) {
            if (row != null && row.name() != null) {
                names.put(row.id(), row.name());
            }
        }
        return names;
    }

    static ReportDto toDto(TerritoryReport report) {
        List<OverlapDto> overlaps = report.overlaps().stream()
                .map(o -> new OverlapDto(HolderDto.of(o.holderA()), HolderDto.of(o.holderB()), o.chunks())).toList();
        List<StructureDto> structures = report.structures().stream()
                .map(s -> new StructureDto(s.structureId(), s.flag(), HolderDto.of(s.holderA()), HolderDto.of(s.holderB())))
                .toList();
        return new ReportDto(report.world(), report.inputsVersion(), overlaps, structures);
    }
}
