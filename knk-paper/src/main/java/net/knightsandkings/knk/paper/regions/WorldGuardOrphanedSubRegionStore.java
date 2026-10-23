package net.knightsandkings.knk.paper.regions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.regions.OrphanedSubRegions;
import net.knightsandkings.knk.core.regions.SubRegionMirror;
import net.knightsandkings.knk.paper.regions.access.DomainAccessFlags;

/**
 * {@link OrphanedSubRegions.Store} on WorldGuard (KNG-258). The {@code knk-orphaned-since} flag is read and written by
 * name ({@link RegionBookkeepingFlags}), so it is found again after a restart. Main thread only.
 */
public final class WorldGuardOrphanedSubRegionStore implements OrphanedSubRegions.Store {


    private final Supplier<List<RegionManager>> managers;
    private final Set<RegionManager> dirty = new HashSet<>();

    public WorldGuardOrphanedSubRegionStore(Supplier<List<RegionManager>> managers) {
        this.managers = managers;
    }

    @Override
    public List<OrphanedSubRegions.RegionRef> subRegionNamedRegions() {
        List<OrphanedSubRegions.RegionRef> refs = new ArrayList<>();
        for (RegionManager manager : managers.get()) {
            for (ProtectedRegion region : manager.getRegions().values()) {
                if (SubRegionMirror.isSubRegionName(region.getId())) {
                    refs.add(new OrphanedSubRegions.RegionRef(manager.getName(), region.getId(),
                        RegionBookkeepingFlags.get(region, RegionBookkeepingFlags.ORPHANED_SINCE)));
                }
            }
        }
        return refs;
    }

    @Override
    public void markOrphaned(String world, String regionId, long orphanedSince) {
        RegionManager manager = managerOf(world);
        ProtectedRegion region = manager == null ? null : manager.getRegion(regionId);
        if (region == null) {
            throw new IllegalStateException("region not found");
        }
        if (DomainAccessFlags.registered()) {
            region.setFlag(DomainAccessFlags.entry(), null);
            region.setFlag(DomainAccessFlags.exit(), null);
            region.setFlag(DomainAccessFlags.name(), null);
        }
        if (DomainAccessFlags.domainId() != null) {
            region.setFlag(DomainAccessFlags.domainId(), null);
        }
        try {
            region.setParent(null);
        } catch (ProtectedRegion.CircularInheritanceException impossible) {
            // Clearing a parent never forms a cycle.
        }
        RegionBookkeepingFlags.set(region, RegionBookkeepingFlags.ORPHANED_SINCE, String.valueOf(orphanedSince));
        dirty.add(manager);
    }

    @Override
    public void clearOrphaned(String world, String regionId) {
        RegionManager manager = managerOf(world);
        ProtectedRegion region = manager == null ? null : manager.getRegion(regionId);
        if (region != null) {
            RegionBookkeepingFlags.set(region, RegionBookkeepingFlags.ORPHANED_SINCE, null);
            dirty.add(manager);
        }
    }

    @Override
    public void persist() {
        String firstFailure = null;
        for (RegionManager manager : Set.copyOf(dirty)) {
            try {
                manager.saveChanges();
                dirty.remove(manager);
            } catch (Exception e) {
                if (firstFailure == null) {
                    firstFailure = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                }
            }
        }
        if (firstFailure != null) {
            throw new IllegalStateException(firstFailure);
        }
    }

    private RegionManager managerOf(String world) {
        for (RegionManager manager : managers.get()) {
            if (world == null || world.equalsIgnoreCase(manager.getName())) {
                return manager;
            }
        }
        return null;
    }
}
