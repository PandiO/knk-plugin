package net.knightsandkings.knk.api.impl;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.AuthProvider;
import net.knightsandkings.knk.api.dto.LocationRetentionDtos;
import net.knightsandkings.knk.core.domain.location.LocationOrphanDigest;
import net.knightsandkings.knk.core.domain.location.LocationOrphanEntry;
import net.knightsandkings.knk.core.domain.location.LocationOrphanPage;
import net.knightsandkings.knk.core.domain.location.LocationTeleportTarget;
import net.knightsandkings.knk.core.exception.ApiException;
import net.knightsandkings.knk.core.ports.api.LocationRetentionApi;
import okhttp3.OkHttpClient;

/** knk-web-api's LocationRetentionController (KNG-80): the orphan list and the teleport target. */
public class LocationRetentionApiImpl extends BaseApiImpl implements LocationRetentionApi {
    // baseUrl is expected to already include /api
    static final String ENDPOINT = "/location-retention";

    public LocationRetentionApiImpl(String baseUrl, OkHttpClient httpClient, ObjectMapper objectMapper,
                                    AuthProvider authProvider, ExecutorService executor, boolean debugLogging) {
        super(baseUrl, httpClient, objectMapper, authProvider, executor, debugLogging);
    }

    @Override
    public CompletableFuture<LocationOrphanPage> listOrphans(String status, int page, int pageSize) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/orphans?status=" + URLEncoder.encode(status == null ? "open" : status, StandardCharsets.UTF_8)
                + "&page=" + Math.max(1, page) + "&pageSize=" + Math.max(1, pageSize);
            try {
                return map(parse(get(url), LocationRetentionDtos.OrphanPage.class, url));
            } catch (ApiException | IOException e) {
                throw new RuntimeException("Failed to list the orphaned Locations", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<LocationTeleportTarget>> teleportTarget(int locationId) {
        return CompletableFuture.supplyAsync(() -> {
            String url = baseUrl + ENDPOINT + "/locations/" + locationId + "/teleport-target";
            try {
                LocationRetentionDtos.TeleportTarget dto = parse(get(url), LocationRetentionDtos.TeleportTarget.class, url);
                return Optional.ofNullable(dto).map(d -> new LocationTeleportTarget(d.id(), d.name(), d.world(), d.x(), d.y(), d.z(), d.yaw(), d.pitch()));
            } catch (ApiException e) {
                if (e.getStatusCode() == 404) {
                    return Optional.<LocationTeleportTarget>empty();
                }
                throw new RuntimeException("Failed to load Location " + locationId, e);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load Location " + locationId, e);
            }
        }, executor);
    }

    static LocationOrphanPage map(LocationRetentionDtos.OrphanPage dto) {
        if (dto == null) {
            return new LocationOrphanPage(java.util.List.of(), 0, 1, 1, 0);
        }
        var items = dto.items() == null ? java.util.List.<LocationOrphanEntry>of() : dto.items().stream()
            .map(o -> new LocationOrphanEntry(o.id(), o.locationId(), o.status(), o.world(), o.x(), o.y(), o.z(), o.flaggedAt(),
                o.previousDecision() != null ? nonBlank(o.previousDecision().decidedByUsername(), "staff") : null,
                o.previousDecision() != null ? o.previousDecision().decisionNote() : null))
            .toList();
        return new LocationOrphanPage(items, dto.totalCount(), dto.pageNumber(), dto.pageSize(), dto.openCount());
    }

    /** The digest payload of a player notification; null when absent. */
    public static LocationOrphanDigest mapDigest(LocationRetentionDtos.DigestNotification dto) {
        return dto == null ? null : new LocationOrphanDigest(dto.runId(), dto.newCount(), dto.openCount());
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
