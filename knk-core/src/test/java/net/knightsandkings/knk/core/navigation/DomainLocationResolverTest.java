package net.knightsandkings.knk.core.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.districts.DistrictDetail;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.structures.StructureDetail;
import net.knightsandkings.knk.core.domain.towns.TownDetail;
import net.knightsandkings.knk.core.navigation.DomainLocationResolver.DomainPlace;

/** A domain's own Location (road navigation plan R20): embedded, looked up by id, or none. */
class DomainLocationResolverTest {

    private final KnkLocation square = new KnkLocation(30, "Kardenna Square", 10.0, 65.0, 20.0, 0f, 0f, "world");

    private final DomainLocationResolver resolver = new DomainLocationResolver(
        id -> CompletableFuture.completedFuture(id == 30 ? Optional.of(square) : Optional.empty()),
        id -> CompletableFuture.completedFuture(id == 1
            ? Optional.of(new TownDetail(1, "Kardenna", null, null, true, true, "kardenna", 30,
                new TownDetail.Location(30, "Kardenna Square", 10.0, 65.0, 20.0, 0f, 0f, "world"),
                List.of(), List.of(), List.of(), List.of()))
            : id == 2
                ? Optional.of(new TownDetail(2, "Cinix", null, null, true, true, "cinix", 30, null,
                    List.of(), List.of(), List.of(), List.of()))
                : Optional.empty()),
        id -> CompletableFuture.completedFuture(id == 5
            ? Optional.of(new DistrictDetail(5, "Market", null, null, true, true, "kardenna_market", null,
                new DistrictDetail.Location(31, "Market", 1.0, 2.0, 3.0, 0f, 0f, null), 1, List.of(), null, List.of(), List.of()))
            : Optional.empty()),
        id -> CompletableFuture.completedFuture(id == 9
            ? Optional.of(new StructureDetail(9, "Mill", null, null, true, true, "kardenna_mill", 30, null, 5, 1))
            : id == 10
                ? Optional.of(new StructureDetail(10, "Ruin", null, null, true, true, null, null, null, null, null))
                : Optional.empty()));

    @Test
    void aTownUsesItsEmbeddedLocation() {
        Optional<KnkLocation> location = resolver.townLocation(1).join();
        assertTrue(location.isPresent());
        assertEquals("Kardenna Square", location.get().name());
        DomainPlace place = resolver.town(1).join().orElseThrow();
        assertEquals("Town", place.domainType());
        assertEquals("kardenna", place.wgRegionId());
        assertEquals(30, place.location().orElseThrow().id());
    }

    @Test
    void aTownWithoutAnEmbeddedLocationLooksItsIdUp() {
        assertEquals(square, resolver.townLocation(2).join().orElseThrow());
        assertEquals(square, resolver.byType("town", 2).join().orElseThrow().location().orElseThrow());
    }

    @Test
    void anEmbeddedLocationWithoutAWorldDoesNotCount() {
        // the district embeds a Location with world == null and has no locationId
        assertFalse(resolver.districtLocation(5).join().isPresent());
        DomainPlace place = resolver.district(5).join().orElseThrow();
        assertEquals("kardenna_market", place.wgRegionId());
        assertTrue(place.location().isEmpty());
    }

    @Test
    void aStructureOnlyHasALocationId() {
        assertEquals(square, resolver.structureLocation(9).join().orElseThrow());
        assertTrue(resolver.structureLocation(10).join().isEmpty());
        assertEquals("Structure", resolver.byType("STRUCTURE", 9).join().orElseThrow().domainType());
    }

    @Test
    void unknownIdsAndTypesResolveToNothing() {
        assertTrue(resolver.town(77).join().isEmpty());
        assertTrue(resolver.byType("kingdom", 1).join().isEmpty());
        assertTrue(resolver.byType(null, 1).join().isEmpty());
    }
}
