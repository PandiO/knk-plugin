package net.knightsandkings.knk.core.regions.managed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An in-memory stand-in for WorldGuard's region manager: regions with priority, parent, flags, owners and members, the
 * flags the "server" knows about, and switches to make an apply or the save fail. Also resolves a flag at a spot the way
 * WorldGuard does (highest priority region that sets it, walking a region's parents for flags it leaves unset).
 */
final class FakeWorldGuard implements ManagedRegionStore {

    static final class Region {
        final String id;
        int priority;
        String parent;
        final Map<String, Object> flags = new LinkedHashMap<>();
        final Set<String> owners = new HashSet<>();
        final Set<String> members = new HashSet<>();

        Region(String id) {
            this.id = id;
        }

        Region snapshot() {
            Region copy = new Region(id);
            copy.priority = priority;
            copy.parent = parent;
            copy.flags.putAll(flags);
            copy.owners.addAll(owners);
            copy.members.addAll(members);
            return copy;
        }
    }

    final Map<String, Region> regions = new LinkedHashMap<>();
    final Set<String> knownFlags = new HashSet<>(Set.of("pvp", "mob-spawning", "damage-animals", "entity-item-frame-destroy",
            "deny-message", "entry", "entry-deny-message", "feed-amount", "feed-delay", "block-break", "build", "greeting"));
    final Set<String> failApplyFor = new HashSet<>();
    boolean failPersist;
    int applyCalls;
    int persistCalls;

    Region add(String id) {
        Region region = new Region(id);
        regions.put(id.toLowerCase(Locale.ROOT), region);
        return region;
    }

    Region get(String id) {
        return regions.get(id.toLowerCase(Locale.ROOT));
    }

    Map<String, Region> snapshotAll() {
        Map<String, Region> copy = new HashMap<>();
        regions.forEach((key, region) -> copy.put(key, region.snapshot()));
        return copy;
    }

    @Override
    public Optional<ActualRegionState> read(String regionId) {
        Region region = get(regionId);
        return region == null ? Optional.empty()
                : Optional.of(new ActualRegionState(region.id, region.parent, region.priority, region.flags));
    }

    @Override
    public boolean supportsFlag(String flagName) {
        return knownFlags.contains(flagName);
    }

    @Override
    public void apply(String regionId, RegionChange change) throws RegionStoreException {
        applyCalls++;
        if (failApplyFor.contains(regionId)) {
            throw new RegionStoreException("simulated WorldGuard failure");
        }
        Region region = get(regionId);
        if (change.newPriority() != null) {
            region.priority = change.newPriority();
        }
        if (change.newParentRegionId() != null) {
            region.parent = get(change.newParentRegionId()).id;
        }
        region.flags.putAll(change.flagsToSet());
    }

    @Override
    public void persist() throws RegionStoreException {
        persistCalls++;
        if (failPersist) {
            throw new RegionStoreException("disk full");
        }
    }

    /** The flag's effective value at a spot covered by {@code regionIds}, or null when nothing sets it. */
    Object resolve(String flag, String... regionIds) {
        List<Region> covering = new ArrayList<>();
        for (String id : regionIds) {
            covering.add(get(id));
        }
        covering.sort((a, b) -> Integer.compare(b.priority, a.priority));
        for (Region region : covering) {
            Region current = region;
            while (current != null) {
                if (current.flags.containsKey(flag)) {
                    return current.flags.get(flag);
                }
                current = current.parent == null ? null : get(current.parent);
            }
        }
        return null;
    }
}
