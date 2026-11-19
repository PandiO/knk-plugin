package net.knightsandkings.knk.paper.regions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.map.RegionOutline;
import net.knightsandkings.knk.paper.http.TerritoryLookupHandler;
import net.knightsandkings.knk.paper.map.RegionOutlineAdapter;

/**
 * P2c (PandiO/knk-workspace#677): a region and its domain's sub-regions ({@link DomainRegionUnion}, KNG-257) as outlines,
 * for the territory lookup's {@code contains-region}. In the named world only (KNG-112), else the first loaded world that
 * has the region. Main thread.
 */
public final class RegionUnionOutlines implements TerritoryLookupHandler.RegionUnionSource {

    private final Supplier<List<RegionManager>> managers;

    public RegionUnionOutlines(Supplier<List<RegionManager>> managers) {
        this.managers = managers;
    }

    @Override
    public Optional<List<RegionOutline>> union(String world, String regionId) {
        if (regionId == null || regionId.isBlank()) {
            return Optional.empty();
        }
        for (RegionManager manager : managers.get()) {
            if (world != null && !world.equalsIgnoreCase(manager.getName())) {
                continue;
            }
            ProtectedRegion region = manager.getRegion(regionId.trim());
            if (region == null) {
                continue;
            }
            List<RegionOutline> outlines = new ArrayList<>();
            for (ProtectedRegion member : DomainRegionUnion.of(manager, region)) {
                RegionOutlineAdapter.outlineOf(member, manager.getName()).ifPresent(outlines::add);
            }
            return Optional.of(outlines);
        }
        return Optional.empty();
    }
}
