package net.knightsandkings.knk.api.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.api.dto.PlayerNotificationDto;
import net.knightsandkings.knk.api.mapper.UsersMapper;
import net.knightsandkings.knk.core.domain.location.LocationOrphanPage;
import net.knightsandkings.knk.core.domain.location.LocationTeleportTarget;
import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** KNG-80: request/response JSON matches knk-web-api's LocationRetentionController and LocationRetentionDtos.cs. */
class LocationRetentionApiImplTest {

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final List<Request> seen = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private int status = 200;
    private String responseJson = "{}";

    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
            .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();

    private final LocationRetentionApiImpl api = new LocationRetentionApiImpl("http://api.test/api", client, mapper,
        new NoAuthProvider(), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void listOrphans_readsThePage_andTheEarlierKeepDecision() {
        responseJson = """
            {"items":[{"id":3,"locationId":41,"status":"Open","flaggedAt":"2026-10-04T04:00:00","world":"world","x":1.5,"y":64,"z":-2,
              "yaw":0,"pitch":0,"locationExists":true,
              "previousDecision":{"itemId":1,"status":"Kept","decidedByUsername":"mod","decisionNote":"Event marker"}}],
             "totalCount":9,"pageNumber":2,"pageSize":8,"openCount":9,"keptCount":1}""";

        LocationOrphanPage page = api.listOrphans("open", 2, 8).join();

        assertEquals("http://api.test/api/location-retention/orphans?status=open&page=2&pageSize=8", seen.get(0).url().toString());
        assertEquals("GET", seen.get(0).method());
        assertEquals(2, page.totalPages());
        assertEquals(9, page.openCount());
        var entry = page.items().get(0);
        assertEquals(41, entry.locationId());
        assertEquals(1.5, entry.x());
        assertEquals("mod", entry.previouslyKeptBy());
        assertEquals("Event marker", entry.previousNote());
    }

    @Test
    void teleportTarget_readsTheLocation_andA404IsEmpty() {
        responseJson = "{\"id\":42,\"name\":\"Location\",\"world\":\"world_nether\",\"x\":10.5,\"y\":70,\"z\":-3,\"yaw\":90,\"pitch\":5}";
        LocationTeleportTarget target = api.teleportTarget(42).join().orElseThrow();
        assertEquals("http://api.test/api/location-retention/locations/42/teleport-target", seen.get(0).url().toString());
        assertEquals("world_nether", target.world());
        assertEquals(90f, target.yaw());

        status = 404;
        responseJson = "{\"error\":\"LocationNotFound\",\"message\":\"Location 43 not found.\"}";
        assertTrue(api.teleportTarget(43).join().isEmpty());
    }

    @Test
    void aDigestNotification_isMapped() throws Exception {
        PlayerNotificationDto dto = mapper.readValue("""
            {"id":5,"userId":0,"uuid":null,"username":"","type":"LocationOrphanDigest",
             "locationOrphanDigest":{"runId":12,"newCount":3,"openCount":7},"createdAt":"2026-10-04T04:00:01"}""",
            PlayerNotificationDto.class);

        PlayerNotification notification = UsersMapper.mapPlayerNotification(dto);

        assertEquals(PlayerNotification.TYPE_LOCATION_ORPHAN_DIGEST, notification.type());
        assertEquals(12, notification.locationOrphanDigest().runId());
        assertEquals(3, notification.locationOrphanDigest().newCount());
        assertEquals(7, notification.locationOrphanDigest().openCount());
        assertNull(notification.currencyAlert());
    }
}
