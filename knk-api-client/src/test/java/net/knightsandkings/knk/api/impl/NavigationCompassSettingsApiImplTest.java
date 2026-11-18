package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.api.auth.NoAuthProvider;
import net.knightsandkings.knk.core.navigation.compass.CompassItemRule.NameMatch;
import net.knightsandkings.knk.core.navigation.compass.CompassSettings;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GET /api/navigation-settings/compass (KNG-234). */
class NavigationCompassSettingsApiImplTest {

    private final List<Request> seen = new ArrayList<>();
    private String responseJson = "{}";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
        Request request = chain.request();
        seen.add(request);
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(responseJson, MediaType.get("application/json"))).build();
    }).build();
    private final NavigationCompassSettingsApiImpl api = new NavigationCompassSettingsApiImpl("http://api.test/api", client,
            new ObjectMapper(), new NoAuthProvider(), executor, false);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void readsTheItemRuleAndTheTargets() {
        responseJson = """
                {"enabled":true,"material":"minecraft:recovery_compass","displayName":"&6Siege Compass","nameMatch":"Contains",
                 "siegeEnabled":true,"generalEnabled":true,"generalTargets":"town:Kardenna\\n\\nMarket Square spawn",
                 "updatedAt":"2026-10-22T08:00:00","somethingNew":1}
                """;

        CompassSettings settings = api.get().join();

        assertEquals("GET", seen.get(0).method());
        assertEquals("http://api.test/api/navigation-settings/compass", seen.get(0).url().toString());
        assertEquals("RECOVERY_COMPASS", settings.item().material());
        assertEquals(NameMatch.CONTAINS, settings.item().nameMatch());
        assertTrue(settings.item().matches("RECOVERY_COMPASS", "§aMy siege compass II"));
        assertFalse(settings.item().matches("COMPASS", "Siege Compass"));
        assertTrue(settings.siegeEnabled());
        assertTrue(settings.generalEnabled());
        assertEquals(List.of("town:Kardenna", "Market Square spawn"), settings.generalTargets());
    }

    @Test
    void aDisabledCompassMatchesNoItem() {
        responseJson = """
                {"enabled":false,"material":"COMPASS","displayName":"Navigation Compass","nameMatch":"Exact",
                 "siegeEnabled":true,"generalEnabled":false,"generalTargets":""}
                """;

        assertFalse(api.get().join().item().matches("COMPASS", "Navigation Compass"));
    }
}
