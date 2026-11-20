package net.knightsandkings.knk.api.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlockOffer;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlockPurchaseResult;
import net.knightsandkings.knk.core.siege.unlocks.SiegeUnlocksOverview;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Siege unlocks overview and a purchase refusal as knk-web-api's {@code SiegeWeaponUnlocksController} sends them
 * (siege shop § 6.2.3-6.2.4; PandiO/knk-workspace#869).
 */
class SiegeWeaponUnlocksApiImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String JSON = """
            {"userId":7,"coins":1200,"titleLevel":1,"titleName":"Peasant","offers":[
              {"templateId":3,"name":"Ballista","kind":"Direct","unlockPrice":500,"minTitleLevel":null,"minTitleName":null,
               "unlocked":false,"unlockedAt":null,"meetsTitle":true,"canAfford":true,"state":"Unlockable"},
              {"templateId":4,"name":"Trebuchet","kind":"Indirect","unlockPrice":1000,"minTitleLevel":2,"minTitleName":"Squire",
               "unlocked":false,"meetsTitle":false,"canAfford":true,"state":"NeedsTitle"},
              {"templateId":null,"name":"broken"}]}
            """;

    @Test
    void mapsTheOverview() throws Exception {
        SiegeUnlocksOverview overview = SiegeWeaponUnlocksApiImpl.toCore(
                objectMapper.readValue(JSON, SiegeWeaponUnlocksApiImpl.OverviewDto.class), 7);

        assertEquals(1200, overview.coins());
        assertEquals("Peasant", overview.titleName());
        assertEquals(List.of(3, 4), overview.offers().stream().map(SiegeUnlockOffer::templateId).toList());
        SiegeUnlockOffer trebuchet = overview.offers().get(1);
        assertEquals(SiegeUnlockOffer.State.NEEDS_TITLE, trebuchet.state());
        assertEquals("Squire", trebuchet.minTitleName());
        assertEquals(1000, trebuchet.unlockPrice());
        assertNull(overview.offers().get(0).minTitleName());
    }

    @Test
    void anEmptyAnswerIsAnEmptyOverview() {
        assertTrue(SiegeWeaponUnlocksApiImpl.toCore(null, 7).offers().isEmpty());
    }

    @Test
    void aConflictBodyIsTheRefusalReason() {
        SiegeWeaponUnlocksApiImpl api = new SiegeWeaponUnlocksApiImpl("http://localhost/api", null, objectMapper, null, null, false);
        SiegeUnlockPurchaseResult.Refused refused = api.refusal(
                "{\"code\":\"InsufficientFunds\",\"message\":\"Not enough coins to unlock the Ballista: 300/500.\"}");
        assertEquals("InsufficientFunds", refused.code());
        assertEquals("Not enough coins to unlock the Ballista: 300/500.", refused.message());
        assertNull(api.refusal("not json").code());
    }
}
