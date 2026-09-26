package net.knightsandkings.knk.paper.teleport;

import net.knightsandkings.knk.core.dataaccess.DistrictsDataAccess;
import net.knightsandkings.knk.core.dataaccess.FetchPolicy;
import net.knightsandkings.knk.core.dataaccess.FetchResult;
import net.knightsandkings.knk.core.dataaccess.LocationsDataAccess;
import net.knightsandkings.knk.core.dataaccess.StructuresDataAccess;
import net.knightsandkings.knk.core.dataaccess.TownsDataAccess;
import net.knightsandkings.knk.core.domain.districts.DistrictDetail;
import net.knightsandkings.knk.core.domain.location.KnkLocation;
import net.knightsandkings.knk.core.domain.settings.KnkGameSettings;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference;
import net.knightsandkings.knk.core.domain.settings.KnkSpawnReference.SourceType;
import net.knightsandkings.knk.core.domain.structures.StructureDetail;
import net.knightsandkings.knk.core.domain.towns.TownDetail;
import net.knightsandkings.knk.core.ports.api.GameSettingsQueryApi;
import net.knightsandkings.knk.core.teleport.SpawnPoint;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The /spawn destination through the real data-access gateways (docs/specs/teleport/DESIGN.md §3.6,
 * Phase 4): a Town/District's embedded Location, a Structure's locationId, and the Bukkit side
 * (loaded world, unloaded world, world spawn).
 */
class SpawnDestinationResolverTest {

    private final GameSettingsQueryApi gameSettings = mock(GameSettingsQueryApi.class);
    private final LocationsDataAccess locations = mock(LocationsDataAccess.class);
    private final TownsDataAccess towns = mock(TownsDataAccess.class);
    private final DistrictsDataAccess districts = mock(DistrictsDataAccess.class);
    private final StructuresDataAccess structures = mock(StructuresDataAccess.class);
    private final World world = mock(World.class);
    private final Location worldSpawn = new Location(world, 0.5, 64, 0.5);
    private final SpawnDestinationResolver resolver = SpawnDestinationResolver.create(gameSettings, locations, towns,
        districts, structures, name -> "world".equals(name) ? world : null, () -> world);

    SpawnDestinationResolverTest() {
        when(world.getSpawnLocation()).thenReturn(worldSpawn);
    }

    private void spawnIs(SourceType type, int id) {
        when(gameSettings.get()).thenReturn(CompletableFuture.completedFuture(new KnkGameSettings("CustomReference",
            new KnkSpawnReference(type, id, type + " spawn", null))));
    }

    private void locationIs(int id, KnkLocation location) {
        when(locations.getByIdAsync(eq(id), eq(FetchPolicy.API_THEN_CACHE_REFRESH)))
            .thenReturn(CompletableFuture.completedFuture(FetchResult.missFetched(location)));
    }

    private static TownDetail town(Integer locationId, TownDetail.Location location) {
        return new TownDetail(4, "Kardenna", null, null, true, true, "kardenna", locationId, location,
            List.of(), List.of(), List.of(), List.of());
    }

    private static DistrictDetail district(Integer locationId, DistrictDetail.Location location) {
        return new DistrictDetail(3, "Market", null, null, true, true, "market", locationId, location, 4,
            List.of(), null, List.of(), List.of());
    }

    @Test
    void townUsesItsEmbeddedLocation() {
        spawnIs(SourceType.TOWN, 4);
        when(towns.getByIdAsync(eq(4), eq(FetchPolicy.API_THEN_CACHE_REFRESH))).thenReturn(CompletableFuture.completedFuture(
            FetchResult.missFetched(town(12, new TownDetail.Location(12, "Kardenna", 100.5, 70.0, -20.5, 90f, 0f, "world")))));

        SpawnPoint point = resolver.resolve().join();

        assertEquals(SpawnPoint.Source.REFERENCE, point.source());
        assertEquals(100.5, point.location().x());
        verify(locations, never()).getByIdAsync(anyInt(), eq(FetchPolicy.API_THEN_CACHE_REFRESH));
    }

    @Test
    void districtWithoutAnEmbeddedLocationLooksUpItsLocationId() {
        spawnIs(SourceType.DISTRICT, 3);
        when(districts.getByIdAsync(eq(3), eq(FetchPolicy.API_THEN_CACHE_REFRESH)))
            .thenReturn(CompletableFuture.completedFuture(FetchResult.missFetched(district(21, null))));
        locationIs(21, new KnkLocation(21, "Market", 5.0, 65.0, 6.0, 0f, 0f, "world"));

        SpawnPoint point = resolver.resolve().join();

        assertEquals(5.0, point.location().x());
    }

    @Test
    void structureLooksUpItsLocationId() {
        spawnIs(SourceType.STRUCTURE, 8);
        when(structures.getByIdAsync(eq(8), eq(FetchPolicy.API_THEN_CACHE_REFRESH))).thenReturn(CompletableFuture.completedFuture(
            FetchResult.missFetched(new StructureDetail(8, "Keep", null, null, true, true, "keep", 31, 2, 3, 1))));
        locationIs(31, new KnkLocation(31, "Keep", -7.0, 80.0, 9.0, 0f, 0f, "world"));

        SpawnPoint point = resolver.resolve().join();

        assertEquals(-7.0, point.location().x());
    }

    @Test
    void domainNotFoundFallsBackToTheWorldSpawn() {
        spawnIs(SourceType.TOWN, 4);
        when(towns.getByIdAsync(eq(4), eq(FetchPolicy.API_THEN_CACHE_REFRESH)))
            .thenReturn(CompletableFuture.completedFuture(FetchResult.notFound()));

        SpawnPoint point = resolver.resolve().join();

        assertEquals(SpawnPoint.Source.WORLD_SPAWN, point.source());
        assertSame(worldSpawn, resolver.toLocation(point));
    }

    @Test
    void invalidateReadsTheGameSettingsAgain() {
        when(gameSettings.get()).thenReturn(CompletableFuture.completedFuture(new KnkGameSettings("WorldSpawn", null)));
        resolver.resolve().join();
        resolver.resolve().join();

        resolver.invalidate();
        resolver.resolve().join();

        verify(gameSettings, times(2)).get();
    }

    @Test
    void toLocationUsesTheNamedWorldAndDefaultsMissingRotation() {
        Location location = resolver.toLocation(new SpawnPoint(
            new KnkLocation(1, "x", 1.5, 70.0, -2.5, null, null, "world"), "x", SpawnPoint.Source.REFERENCE));

        assertSame(world, location.getWorld());
        assertEquals(1.5, location.getX());
        assertEquals(0f, location.getYaw());
        assertEquals(0f, location.getPitch());
    }

    @Test
    void toLocationInAnUnloadedWorldUsesTheWorldSpawn() {
        Location location = resolver.toLocation(new SpawnPoint(
            new KnkLocation(1, "x", 1.5, 70.0, -2.5, 0f, 0f, "old_world"), "x", SpawnPoint.Source.REFERENCE));

        assertSame(worldSpawn, location);
    }

    @Test
    void noWorldAtAllGivesNoLocation() {
        SpawnDestinationResolver noWorlds = SpawnDestinationResolver.create(gameSettings, locations, towns, districts,
            structures, name -> null, () -> null);

        assertNull(noWorlds.toLocation(SpawnPoint.worldSpawn()));
    }
}
