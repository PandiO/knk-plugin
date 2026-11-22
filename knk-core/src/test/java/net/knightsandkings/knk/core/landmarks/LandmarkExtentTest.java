package net.knightsandkings.knk.core.landmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.landmarks.KnkLandmark;
import net.knightsandkings.knk.core.domain.landmarks.LandmarkAreaKind;
import net.knightsandkings.knk.core.navigation.model.Landmark;

/**
 * The extent of a landmark: off-road phase 5.1 (PandiO/knk-workspace#708), since PandiO/knk-workspace#985
 * (landmark-domain DESIGN § 5 rule 3, LD08, LD22) a point or the union of its area's regions, without the cylinder.
 */
class LandmarkExtentTest {

    private static Landmark landmark(String... regions) {
        return new Landmark(1, "x", "world", 10, 64, 10, Arrays.asList(regions), null, null, true);
    }

    private static KnkLandmark api(LandmarkAreaKind kind, String own, List<String> subs, String parentRegion) {
        return new KnkLandmark(7, "Tower", 3, "world", 1, 64, 2, 0f, 0f, parentRegion == null ? null : 2, "Rivia", "Town",
            parentRegion, kind, own, subs, false, 1.0, true);
    }

    @Test
    void aLandmarkWithoutRegionsIsAPoint_OneWithRegionsIsTheirUnion() {
        assertInstanceOf(LandmarkExtent.Point.class, LandmarkExtent.of(landmark()));
        assertEquals(new LandmarkExtent.Area(List.of("domain_7")), LandmarkExtent.of(landmark("domain_7")));
        LandmarkExtent.Area area = (LandmarkExtent.Area) LandmarkExtent.of(landmark("domain_7", "domain_7_sub_1", "domain_7_sub_2"));
        assertEquals("domain_7", area.mainRegionId());
        assertEquals(List.of("domain_7_sub_1", "domain_7_sub_2"), area.otherRegionIds());
        assertThrows(IllegalArgumentException.class, () -> new LandmarkExtent.Area(List.of(" ")));
    }

    @Test
    void theRegionsAreTrimmed_BlanksAndRepeatsDropped_MainFirst() {
        Landmark l = landmark(" domain_7 ", "", null, "DOMAIN_7", "domain_7_sub_1");
        assertEquals(List.of("domain_7", "domain_7_sub_1"), l.regionIds());
        assertEquals("domain_7", l.mainRegionId());
        assertTrue(l.hasArea());
        assertNull(landmark().mainRegionId());
    }

    /** A08–A14 data side (LD16, LD22): the area of each kind, from {@code api/landmarks}. */
    @Test
    void theAreaKind_GivesTheAreasRegions() {
        assertEquals(List.of(), api(LandmarkAreaKind.NONE, null, List.of(), null).areaRegionIds(), "a point");
        assertEquals(List.of("domain_7", "domain_7_sub_1"),
            api(LandmarkAreaKind.OWN_REGION, "domain_7", List.of("domain_7_sub_1"), "town_1").areaRegionIds(),
            "its own main region and sub-regions, not the parent's");
        assertEquals(List.of("town_1"), api(LandmarkAreaKind.LINKED_DOMAIN, null, List.of(), "town_1").areaRegionIds(),
            "the parent's main region (its sub-regions are found by knk-domain-id on the server)");
        assertEquals(List.of(), api(LandmarkAreaKind.OWN_REGION, null, List.of("x"), null).areaRegionIds(),
            "an own region not drawn yet: a point");
        assertEquals(List.of(), api(LandmarkAreaKind.LINKED_DOMAIN, null, List.of(), null).areaRegionIds(),
            "a parent without a region: a point");
        Landmark model = api(LandmarkAreaKind.LINKED_DOMAIN, null, List.of(), "town_1").toModel();
        assertEquals(List.of("town_1"), model.regionIds());
        assertEquals(2, model.domainId());
        assertEquals("Rivia", model.domainName());
    }

    @Test
    void theAreaKindParsesTheApisWords() {
        assertEquals(LandmarkAreaKind.OWN_REGION, LandmarkAreaKind.parse("OwnRegion"));
        assertEquals(LandmarkAreaKind.LINKED_DOMAIN, LandmarkAreaKind.parse("linked_domain"));
        assertEquals(LandmarkAreaKind.NONE, LandmarkAreaKind.parse("Radius"), "the old radius extent is a point (LD08)");
        assertEquals(LandmarkAreaKind.NONE, LandmarkAreaKind.parse(null));
        assertEquals("LinkedDomain", LandmarkAreaKind.LINKED_DOMAIN.apiWord());
    }

    @Test
    void onlyALandmarkWithAnAreaAndAPreferenceIsWeighted() {
        assertFalse(new Landmark(1, "x", "world", 0, 0, 0, List.of(), null, null, true, 2.0).weighted());
        assertFalse(landmark("hall").weighted());
        assertTrue(new Landmark(1, "x", "world", 0, 0, 0, List.of("hall"), null, null, true, 2.0).weighted());
    }

    @Test
    void v1HasNoStoredLinks() {
        assertTrue(LandmarkConnections.INFERRED.linksOf(1).isEmpty());
    }
}
