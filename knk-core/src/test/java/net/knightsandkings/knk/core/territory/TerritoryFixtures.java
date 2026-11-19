package net.knightsandkings.knk.core.territory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.knightsandkings.knk.core.map.OutlinePoint;
import net.knightsandkings.knk.core.map.RegionOutline;

/** Shared test data for the territory tests (PandiO/knk-workspace#689). */
final class TerritoryFixtures {

    static final String WORLD = "world";

    private TerritoryFixtures() {
    }

    /** A cuboid region's outline as WorldGuard gives it: four corners, inclusive block coordinates. */
    static RegionOutline cuboid(String regionId, int minX, int minZ, int maxX, int maxZ) {
        return new RegionOutline(regionId, WORLD, List.of(new OutlinePoint(minX, minZ), new OutlinePoint(maxX, minZ),
                new OutlinePoint(maxX, maxZ), new OutlinePoint(minX, maxZ)), 0, 255);
    }

    static RegionOutline polygon(String regionId, int... xz) {
        List<OutlinePoint> points = new ArrayList<>();
        for (int i = 0; i < xz.length; i += 2) {
            points.add(new OutlinePoint(xz[i], xz[i + 1]));
        }
        return new RegionOutline(regionId, WORLD, points, 0, 255);
    }

    static TerritoryInputs.Source district(int id, String regionId, int townId, double range) {
        return new TerritoryInputs.Source(TerritoryInputs.SourceType.DISTRICT, id, "District " + id, WORLD, regionId,
                List.of(), TerritoryHolder.town(townId), false, range);
    }

    static TerritoryInputs.Source crownLand(int id, String regionId, int kingdomId, double range) {
        return new TerritoryInputs.Source(TerritoryInputs.SourceType.CROWN_LAND, id, "Crown " + id, WORLD, regionId,
                List.of(), TerritoryHolder.kingdom(kingdomId), false, range);
    }

    static TerritoryInputs inputs(List<TerritoryInputs.Source> sources, List<TerritoryInputs.Award> awards) {
        return new TerritoryInputs(true, "v1", Map.of(1, "Phoenix", 2, "Rivertown"), Map.of(7, "Aurelia"), sources, awards);
    }

    static TerritoryCacheBuilder.PlacedSource placed(TerritoryInputs.Source source, RegionOutline... outlines) {
        return new TerritoryCacheBuilder.PlacedSource(source, WORLD, TerritoryShape.of(List.of(outlines)));
    }

    /** Builds the cache of these placed sources. */
    static TerritoryCache build(List<TerritoryInputs.Award> awards, TerritoryCacheBuilder.PlacedSource... placed) {
        List<TerritoryInputs.Source> sources = new ArrayList<>();
        for (TerritoryCacheBuilder.PlacedSource source : placed) {
            sources.add(source.source());
        }
        return TerritoryCacheBuilder.build(inputs(sources, awards), List.of(placed), List.of(), 1L,
                TerritoryCacheBuilder.DEFAULT_MAX_CHUNKS_PER_WORLD);
    }
}
