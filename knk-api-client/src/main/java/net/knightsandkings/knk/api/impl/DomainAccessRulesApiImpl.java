package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.core.domain.domains.DomainAccessRule;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.DomainAccessRulesApi;
import okhttp3.OkHttpClient;

/** {@code GET /api/Domains/access-rules} (KNG-56). */
public class DomainAccessRulesApiImpl extends BaseApiImpl implements DomainAccessRulesApi {

    static final String ENDPOINT = "/Domains/access-rules";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RuleDto(
        @JsonProperty("id") int id,
        @JsonProperty("name") String name,
        @JsonProperty("wgRegionId") String wgRegionId,
        @JsonProperty("allowEntry") boolean allowEntry,
        @JsonProperty("allowExit") boolean allowExit,
        @JsonProperty("domainType") String domainType,
        @JsonProperty("worldName") String worldName
    ) {}

    public DomainAccessRulesApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                    AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<List<DomainAccessRule>> listAccessRules() {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT;
            try {
                List<RuleDto> rules = parse(get(url), new TypeReference<List<RuleDto>>() { }, url);
                return rules == null ? List.<DomainAccessRule>of() : rules.stream()
                    .map(r -> new DomainAccessRule(r.id(), r.name(), r.wgRegionId(), r.allowEntry(), r.allowExit(), r.domainType(),
                        r.worldName()))
                    .toList();
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to read the domain access rules", e);
            }
        }, executor);
    }
}
