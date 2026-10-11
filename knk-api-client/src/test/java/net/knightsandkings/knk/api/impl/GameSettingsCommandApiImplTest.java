package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.ApiKeyAuthProvider;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkRespawnPolicy;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;
import net.knightsandkings.knk.core.domain.settings.KnkWeather;
import net.knightsandkings.knk.core.domain.settings.KnkWeatherSettings;
import net.knightsandkings.knk.core.domain.settings.KnkWorldRuntime;
import net.knightsandkings.knk.core.domain.settings.KnkWorldSettings;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The full Game Settings read and the runtime-worlds report (docs/specs/game-settings/DESIGN.md, KNG-52). */
class GameSettingsCommandApiImplTest {

    /** What knk-web-api's GameSettingsReadDto serializes to, timestamps without an offset as MySQL hands them back. */
    static final String FULL = """
            {"id":"global","settingsVersion":"1",
             "joinAnnouncement":"&6{player} has arrived","leaveAnnouncement":"",
             "joinSpawnMode":"WorldSpawn","joinSpawnReference":null,
             "defaultRespawnPolicy":{"mode":"WorldSpawn","useWorldSpawnFallback":true},
             "worldSettings":[
               {"worldName":"world","worldFolderName":"world","defaultGameMode":"SURVIVAL","lockTime":true,"lockedTime":6000,
                "weather":{"mode":"Weighted","forcedWeather":null,"blockedWeatherTypes":[],"clearWeight":70,"rainWeight":25,"thunderWeight":5},
                "worldSpawnReference":{"sourceType":"Town","sourceId":4,"displayLabel":"Town: Kardenna",
                  "location":{"locationId":12,"x":10.5,"y":64,"z":-3.5,"yaw":90,"pitch":0,"world":"world"}},
                "respawnPolicy":{"mode":"NearestTown","maxNearestTownDistance":500,"useWorldSpawnFallback":false}},
               {"worldName":"world_nether","defaultGameMode":"ADVENTURE","lockTime":false,"lockedTime":18000,
                "weather":{"mode":"Blocked","blockedWeatherTypes":["THUNDER","snow"]},
                "respawnPolicy":{"mode":"ConfiguredReference","locationReference":{"sourceType":"Location","sourceId":7}}}],
             "runtimeWorlds":[{"worldName":"world","folderName":"world","environment":"NORMAL","loaded":true,"playerCount":3,"isPrimary":true}],
             "runtimeWorldsLastUpdatedAt":"2026-10-05T12:00:00","createdAt":"2026-08-19T10:00:00","updatedAt":"2026-10-05T11:59:00",
             "motd":"&6Knights and Kings\\n&e{online} online",
             "groupOverrides":[
               {"permissionGroupId":3,"groupName":"Staff","precedence":2,"joinAnnouncement":"","joinSpawnReference":null,"respawnPolicy":null},
               {"permissionGroupId":2,"groupName":"Noble","precedence":1,"joinAnnouncement":"&6[{group}] {player}",
                "joinSpawnReference":{"sourceType":"Structure","sourceId":9,"displayLabel":"Structure: Lounge"},
                "respawnPolicy":{"mode":"JoinSpawn"}}]}
            """;

    private final List<Request> seen = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();
    private int status = 200;
    private String response = FULL;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        Buffer buffer = new Buffer();
        if (request.body() != null) {
            request.body().writeTo(buffer);
        }
        bodies.add(buffer.readUtf8());
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
                .body(ResponseBody.create(response, MediaType.get("application/json"))).build();
    }).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final GameSettingsCommandApiImpl command = new GameSettingsCommandApiImpl("http://api.test/api", client, mapper,
            new ApiKeyAuthProvider("secret"), executor, false);
    private final GameSettingsQueryApiImpl query = new GameSettingsQueryApiImpl("http://api.test/api", client, mapper,
            new ApiKeyAuthProvider("secret"), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void reportsTheWorldsWithThePluginKey() throws Exception {
        command.reportRuntimeWorlds(List.of(
                new KnkWorldRuntime("world", "world", "NORMAL", true, 3, true),
                new KnkWorldRuntime("world_nether", "world_nether", "NETHER", true, 0, false))).join();

        Request request = seen.get(0);
        assertEquals("PUT", request.method());
        assertEquals("http://api.test/api/GameSettings/runtime-worlds", request.url().toString());
        assertEquals("secret", request.header("X-API-Key"));
        JsonNode body = mapper.readTree(bodies.get(0)).get("runtimeWorlds");
        assertEquals(2, body.size());
        assertEquals("world", body.get(0).get("worldName").asText());
        assertEquals("NORMAL", body.get(0).get("environment").asText());
        assertEquals(3, body.get(0).get("playerCount").asInt());
        assertTrue(body.get(0).get("isPrimary").asBoolean());
        assertTrue(body.get(0).get("loaded").asBoolean());
        assertFalse(body.get(1).get("isPrimary").asBoolean());
    }

    @Test
    void theReportAnswersWithTheSettings() {
        KnkGameSettings settings = command.reportRuntimeWorlds(List.of()).join();

        assertEquals(2, settings.worldSettings().size());
        assertEquals("{\"runtimeWorlds\":[]}", bodies.get(0));
    }

    @Test
    void aRejectedReportFails() {
        status = 401;
        response = "{\"message\":\"no key\"}";

        assertThrows(CompletionException.class, () -> command.reportRuntimeWorlds(List.of()).join());
    }

    @Test
    void readsEverythingThePluginApplies() {
        KnkGameSettings settings = query.get().join();

        assertEquals("&6{player} has arrived", settings.joinAnnouncement());
        assertEquals("", settings.leaveAnnouncement());
        assertEquals("2026-10-05T11:59:00", settings.updatedAt());
        assertTrue(settings.customJoinSpawn().isEmpty());

        KnkWorldSettings world = settings.world("world").orElseThrow();
        assertEquals("SURVIVAL", world.defaultGameMode());
        assertTrue(world.lockTime());
        assertEquals(6000, world.lockedTime());
        assertEquals(KnkWeatherSettings.Mode.WEIGHTED, world.weather().mode());
        assertEquals(70, world.weather().clearWeight());
        assertEquals(5, world.weather().thunderWeight());
        assertEquals(KnkSpawnReference.SourceType.TOWN, world.worldSpawnReference().sourceType());
        assertEquals(10.5, world.worldSpawnReference().snapshot().x());
        assertEquals(KnkRespawnPolicy.Mode.NEAREST_TOWN, world.respawnPolicy().mode());
        assertEquals(500.0, world.respawnPolicy().maxNearestTownDistance());
        assertFalse(world.respawnPolicy().useWorldSpawnFallback());

        KnkWorldSettings nether = settings.world("world_nether").orElseThrow();
        assertEquals(KnkWeatherSettings.Mode.BLOCKED, nether.weather().mode());
        assertEquals(Set.of(KnkWeather.THUNDER), nether.weather().blocked());
        assertEquals(34, nether.weather().clearWeight());
        assertNull(nether.worldSpawnReference());
        assertEquals(KnkRespawnPolicy.Mode.CONFIGURED_REFERENCE, nether.respawnPolicy().mode());
        assertEquals(7, nether.respawnPolicy().reference().sourceId());
        assertTrue(nether.respawnPolicy().useWorldSpawnFallback());
    }

    @Test
    void readsTheMotdAndTheGroupOverridesInPrecedenceOrder() {
        KnkGameSettings settings = query.get().join();

        assertEquals("&6Knights and Kings\n&e{online} online", settings.motd());
        assertEquals(List.of(2, 3), settings.groupOverrides().stream().map(o -> o.permissionGroupId()).toList());
        var noble = settings.groupOverride(2).orElseThrow();
        assertEquals("Noble", noble.groupName());
        assertEquals("&6[{group}] {player}", noble.joinAnnouncement());
        assertEquals(KnkSpawnReference.SourceType.STRUCTURE, noble.joinSpawnReference().sourceType());
        assertEquals(KnkRespawnPolicy.Mode.JOIN_SPAWN, noble.respawnPolicy().mode());
        var staff = settings.groupOverride(3).orElseThrow();
        assertEquals("", staff.joinAnnouncement());
        assertNull(staff.joinSpawnReference());
        assertNull(staff.respawnPolicy());
    }

    @Test
    void userSummariesCarryTheirGroupsInOrder() throws Exception {
        var dto = net.knightsandkings.knk.api.mapper.LootboxMapperTest.apiObjectMapper().readValue("""
                {"id":7,"username":"Steve","coins":1,"gems":2,"experiencePoints":3,"isFullAccount":true,
                 "permissionGroups":[{"id":2,"name":"Noble"},{"id":1,"name":"Default"}]}
                """, net.knightsandkings.knk.api.dto.UserSummaryDto.class);

        var user = net.knightsandkings.knk.api.mapper.UsersMapper.mapUserSummary(dto);

        assertEquals(List.of(new net.knightsandkings.knk.core.domain.users.PermissionGroupRef(2, "Noble"),
                new net.knightsandkings.knk.core.domain.users.PermissionGroupRef(1, "Default")), user.permissionGroups());
        assertEquals(2, net.knightsandkings.knk.api.mapper.UsersMapper.mapUserSummary(user).permissionGroups().size());
        assertTrue(net.knightsandkings.knk.api.mapper.UsersMapper.mapUserSummary(
                net.knightsandkings.knk.api.mapper.LootboxMapperTest.apiObjectMapper().readValue("{\"id\":1}", net.knightsandkings.knk.api.dto.UserSummaryDto.class)).permissionGroups().isEmpty());
    }

    @Test
    void anEmptyAnswerGetsTheDefaults() {
        response = "{}";

        KnkGameSettings settings = query.get().join();

        assertNull(settings.joinAnnouncement());
        assertTrue(settings.worldSettings().isEmpty());
        assertTrue(settings.groupOverrides().isEmpty());
        assertNull(settings.motd());
        assertEquals(KnkRespawnPolicy.Mode.WORLD_SPAWN, settings.defaultRespawnPolicy().mode());
    }
}
