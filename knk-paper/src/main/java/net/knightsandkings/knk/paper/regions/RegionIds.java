package net.knightsandkings.knk.paper.regions;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.bukkit.Location;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;

/**
 * The WorldGuard regions at a location, through one cached {@link RegionQuery} (road navigation
 * plan §2 R8 - the one copy of what {@code WorldGuardRegionTracker}, {@code WorldGuardRegionLookup}
 * and {@code DomainDiscoveryListener} each used to do themselves).
 * <ul>
 *   <li>{@link #applicable(Location)} - WorldGuard's own {@link ApplicableRegionSet}, for callers that
 *       also evaluate flags on it;</li>
 *   <li>{@link #at(Location)} - just the region ids, never {@code __global__}. (WorldGuard's result set
 *       keeps the global region aside for flag queries and does not iterate it, so the filter is a
 *       safety net rather than a behaviour change for the tracker, which never filtered.)</li>
 * </ul>
 * The query is created on first use, so constructing this does no WorldGuard call.
 */
public final class RegionIds {
    public static final String GLOBAL_REGION = "__global__";

    private final Supplier<RegionQuery> querySupplier;
    private volatile RegionQuery query;

    /** Resolves the query from the running WorldGuard on first use. */
    public RegionIds() {
        this(() -> WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery());
    }

    /** A pre-built query (the tracker's) or a test double's. */
    public RegionIds(RegionQuery query) {
        this(() -> query);
        this.query = Objects.requireNonNull(query, "query");
    }

    public RegionIds(Supplier<RegionQuery> querySupplier) {
        this.querySupplier = Objects.requireNonNull(querySupplier, "querySupplier");
    }

    private RegionQuery query() {
        RegionQuery current = query;
        if (current == null) {
            synchronized (this) {
                current = query;
                if (current == null) {
                    current = querySupplier.get();
                    query = current;
                }
            }
        }
        return current;
    }

    /** WorldGuard's applicable regions at the location; null for a location without a world. */
    public ApplicableRegionSet applicable(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return query().getApplicableRegions(BukkitAdapter.adapt(location));
    }

    /** The region ids at the location (a fresh mutable set), excluding {@code __global__}; empty without a world. */
    public Set<String> at(Location location) {
        Set<String> ids = new HashSet<>();
        ApplicableRegionSet set = applicable(location);
        if (set == null) {
            return ids;
        }
        for (ProtectedRegion region : set) {
            if (!GLOBAL_REGION.equalsIgnoreCase(region.getId())) {
                ids.add(region.getId());
            }
        }
        return ids;
    }

    /** As {@link #at(Location)} for block coordinates in a world. */
    public Set<String> at(org.bukkit.World world, int x, int y, int z) {
        return at(new Location(world, x + 0.5, y, z + 0.5));
    }
}
