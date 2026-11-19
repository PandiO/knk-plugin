package net.knightsandkings.knk.core.territory;

import static net.knightsandkings.knk.core.territory.TerritoryFixtures.WORLD;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.build;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.cuboid;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.district;
import static net.knightsandkings.knk.core.territory.TerritoryFixtures.placed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.ports.api.TerritoryApi;

/**
 * The territory lookup the API's {@code InsideTerritory} rule asks (P2c PandiO/knk-workspace#677; contract in knk-web-api
 * {@code TerritoryLookupClient}): every column of the region owned by the holder, contested columns not owned, 503 while
 * territory is off.
 */
class TerritoryLookupTest {

    private static final TerritoryHolder PHOENIX = TerritoryHolder.town(1);
    private static final TerritoryHolder RIVERTOWN = TerritoryHolder.town(2);

    /** Phoenix: district (0..15, 0..15), range 40, so it owns x -40..55 at z 8. Rivertown: district at x 100..115, range 40. */
    private static TerritoryCache twoTowns() {
        return build(List.of(),
                placed(district(10, "d-phoenix", 1, 40), cuboid("d-phoenix", 0, 0, 15, 15)),
                placed(district(20, "d-river", 2, 40), cuboid("d-river", 100, 0, 115, 15)));
    }

    @Test
    void aLocation_isInside_whenItsColumnIsOwnedByTheHolder() {
        TerritoryCache cache = twoTowns();

        TerritoryLookup.Answer inside = TerritoryLookup.location(cache, PHOENIX, WORLD, 30.7, 8.2);
        TerritoryLookup.Answer otherTown = TerritoryLookup.location(cache, RIVERTOWN, WORLD, 30.7, 8.2);
        TerritoryLookup.Answer wilderness = TerritoryLookup.location(cache, PHOENIX, WORLD, -300, 8);

        assertEquals(200, inside.status());
        assertTrue(inside.inside());
        assertTrue(inside.json().startsWith("{\"inside\":true,"), inside.json());
        assertFalse(otherTown.inside());
        assertFalse(wilderness.inside());
        assertTrue(wilderness.json().contains("\"owner\":null"));
    }

    @Test
    void aRegion_isInside_onlyWhenEveryColumnIsOwned() {
        TerritoryCache cache = twoTowns();

        TerritoryLookup.Answer quarry = TerritoryLookup.region(cache, PHOENIX, List.of(cuboid("quarry", 20, 0, 30, 10)));
        TerritoryLookup.Answer straddling = TerritoryLookup.region(cache, PHOENIX, List.of(cuboid("edge", 40, 0, 70, 10)));

        assertTrue(quarry.inside());
        assertEquals(11 * 11 + 4, quarry.columns(), "every column plus the four corners");
        assertEquals(0, quarry.notOwned());
        assertNull(quarry.reason());
        assertFalse(straddling.inside());
        assertTrue(straddling.notOwned() > 0);
        assertTrue(straddling.reason().contains("not owned by Town 1"), straddling.reason());
    }

    @Test
    void contestedColumns_countAsNotOwned() {
        // range 60: Phoenix reaches x 75, Rivertown from x 40, so x 40..75 is contested
        TerritoryCache cache = build(List.of(),
                placed(district(10, "d-phoenix", 1, 60), cuboid("d-phoenix", 0, 0, 15, 15)),
                placed(district(20, "d-river", 2, 60), cuboid("d-river", 100, 0, 115, 15)));
        assertTrue(cache.lookup(WORLD, 57, 8).isContested(), "test setup: x 57 is contested");

        TerritoryLookup.Answer contested = TerritoryLookup.location(cache, PHOENIX, WORLD, 57, 8);
        TerritoryLookup.Answer region = TerritoryLookup.region(cache, PHOENIX, List.of(cuboid("r", 50, 4, 58, 12)));

        assertFalse(contested.inside());
        assertTrue(contested.json().contains("\"contested\":true"));
        assertFalse(region.inside());
    }

    @Test
    void theRegionsUnion_withItsSubRegions_isChecked() {
        TerritoryCache cache = twoTowns();

        TerritoryLookup.Answer union = TerritoryLookup.region(cache, PHOENIX,
                List.of(cuboid("main", 20, 0, 25, 5), cuboid("tower", 200, 0, 205, 5)));

        assertFalse(union.inside(), "the sub-region far away is not Phoenix land");
    }

    @Test
    void aHugeRegion_isSampled_includingItsLastColumnAndCorners() {
        TerritoryCache cache = build(List.of(), placed(district(10, "d", 1, 10), cuboid("d", 0, 0, 5000, 5000)));

        TerritoryLookup.Answer inside = TerritoryLookup.region(cache, PHOENIX, List.of(cuboid("big", 0, 0, 4999, 4999)));
        TerritoryLookup.Answer pokesOut = TerritoryLookup.region(cache, PHOENIX, List.of(cuboid("big", 0, 0, 5011, 4999)));

        assertTrue(inside.inside());
        assertTrue(inside.columns() <= (TerritoryLookup.MAX_SAMPLES_PER_SIDE + 1L) * (TerritoryLookup.MAX_SAMPLES_PER_SIDE + 1L) + 4);
        assertFalse(pokesOut.inside(), "the last column x 5011 is always checked");
    }

    @Test
    void parse_readsTheHolderAndColumn_andNamesTheFirstProblem() {
        Map<String, String> query = new HashMap<>(Map.of("holderKind", "kingdom", "holderId", "7", "x", "1.5", "z", "-3",
                "world", " world "));

        TerritoryLookup.Request request = assertInstanceOf(TerritoryLookup.Request.class, TerritoryLookup.parse(query, true));
        assertEquals(TerritoryHolder.kingdom(7), request.holder());
        assertEquals("world", request.world());
        assertEquals(1.5, request.x());

        query.put("holderKind", "Duchy");
        assertEquals(400, ((TerritoryLookup.Answer) TerritoryLookup.parse(query, true)).status());
        query.put("holderKind", "Town");
        query.put("holderId", "twelve");
        assertTrue(((TerritoryLookup.Answer) TerritoryLookup.parse(query, true)).reason().contains("holderId"));
        query.put("holderId", "12");
        query.remove("x");
        assertTrue(((TerritoryLookup.Answer) TerritoryLookup.parse(query, true)).reason().contains("x and z"));
        assertInstanceOf(TerritoryLookup.Request.class, TerritoryLookup.parse(query, false), "a region needs no x/z");
        query.put("x", "NaN");
        assertEquals(400, ((TerritoryLookup.Answer) TerritoryLookup.parse(query, true)).status());
    }

    @Test
    void withProductionOff_orBeforeTheFirstBuild_territoryIsUnavailable_503() {
        TerritoryLookup.Answer off = TerritoryLookup.unavailable(null);
        assertEquals(503, off.status());
        assertTrue(off.reason().contains("production.enabled: false"));
        assertEquals("{\"inside\":null,\"available\":false,\"reason\":\"territory is off on this game server "
                + "(production.enabled: false)\"}", off.json());

        TerritoryApi api = new TerritoryApi() {
            public CompletableFuture<TerritoryInputs> fetchInputs() {
                return new CompletableFuture<>();
            }

            public CompletableFuture<TerritoryReport.Result> report(TerritoryReport report) {
                return new CompletableFuture<>();
            }
        };
        TerritoryService notBuilt = new TerritoryService(new TerritorySettings(true, 10, true, true, false, 0, Map.of()),
                api, (world, regionId) -> Optional.empty(), null, null, Runnable::run, Runnable::run,
                Logger.getAnonymousLogger());
        assertTrue(TerritoryLookup.unavailable(notBuilt).reason().contains("not built"));
    }

    @Test
    void anEmptyUnion_isNotFound_andTheJsonEscapesText() {
        assertEquals(404, TerritoryLookup.region(twoTowns(), PHOENIX, List.of()).status());
        assertEquals("{\"inside\":null,\"available\":true,\"reason\":\"a \\\"b\\\"\\n\"}",
                TerritoryLookup.Answer.notFound("a \"b\"\n").json());
    }
}
