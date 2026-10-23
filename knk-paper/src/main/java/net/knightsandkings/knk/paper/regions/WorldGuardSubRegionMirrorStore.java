package net.knightsandkings.knk.paper.regions;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.regions.SubRegionMirror;
import net.knightsandkings.knk.core.regions.SubRegionMirror.RegionState;

/**
 * {@link SubRegionMirror.Store} on WorldGuard (KNG-255). Flags are carried by name with WorldGuard's own value objects;
 * owners and members as {@code uuid:}, {@code name:} and {@code group:} entries. Main thread only.
 */
public final class WorldGuardSubRegionMirrorStore implements SubRegionMirror.Store {

    private final Supplier<List<RegionManager>> managers;
    private final Set<RegionManager> dirty = new HashSet<>();

    public WorldGuardSubRegionMirrorStore(Supplier<List<RegionManager>> managers) {
        this.managers = managers;
    }

    @Override
    public Optional<RegionState> read(String world, String regionId) {
        RegionManager manager = managerWith(world, regionId);
        return manager == null ? Optional.empty() : Optional.of(stateOf(manager.getRegion(regionId)));
    }

    @Override
    public void write(String world, String regionId, RegionState desired) {
        RegionManager manager = managerWith(world, regionId);
        if (manager == null) {
            throw new IllegalStateException("region not found");
        }
        ProtectedRegion region = manager.getRegion(regionId);
        ProtectedRegion parent = desired.parentId() == null ? null : manager.getRegion(desired.parentId());
        if (desired.parentId() != null && parent == null) {
            throw new IllegalStateException("main region " + desired.parentId() + " not found in " + manager.getName());
        }
        apply(region, parent, desired, WorldGuard.getInstance().getFlagRegistry());
        dirty.add(manager);
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

    /**
     * Gives {@code region} the mirrored state; on any failure it is put back as it was. Also used right after a
     * sub-region's rename, without the API.
     */
    public static void apply(ProtectedRegion region, ProtectedRegion parent, RegionState desired, FlagRegistry registry) {
        int priority = region.getPriority();
        ProtectedRegion oldParent = region.getParent();
        Map<Flag<?>, Object> oldFlags = new HashMap<>(region.getFlags());
        DefaultDomain oldOwners = new DefaultDomain(region.getOwners());
        DefaultDomain oldMembers = new DefaultDomain(region.getMembers());
        try {
            region.setPriority(desired.priority());
            region.setParent(parent);
            Map<Flag<?>, Object> flags = new HashMap<>();
            for (Map.Entry<Flag<?>, Object> own : oldFlags.entrySet()) {
                if (SubRegionMirror.OWN_FLAGS.contains(own.getKey().getName().toLowerCase(Locale.ROOT))) {
                    flags.put(own.getKey(), own.getValue());
                }
            }
            for (Map.Entry<String, Object> wanted : desired.flags().entrySet()) {
                Flag<?> flag = registry.get(wanted.getKey());
                if (flag == null) {
                    throw new IllegalStateException("unknown flag " + wanted.getKey());
                }
                flags.put(flag, wanted.getValue());
            }
            region.setFlags(flags);
            region.setOwners(domainOf(desired.owners()));
            region.setMembers(domainOf(desired.members()));
        } catch (Exception e) {
            region.setPriority(priority);
            try {
                region.setParent(oldParent);
            } catch (ProtectedRegion.CircularInheritanceException ignored) {
                // It was its parent before.
            }
            region.setFlags(oldFlags);
            region.setOwners(oldOwners);
            region.setMembers(oldMembers);
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getMessage(), e);
        }
    }

    public static RegionState stateOf(ProtectedRegion region) {
        Map<String, Object> flags = new HashMap<>();
        for (Map.Entry<Flag<?>, Object> flag : region.getFlags().entrySet()) {
            flags.put(flag.getKey().getName().toLowerCase(Locale.ROOT), flag.getValue());
        }
        return new RegionState(region.getPriority(), region.getParent() != null ? region.getParent().getId() : null, flags,
            entriesOf(region.getOwners()), entriesOf(region.getMembers()));
    }

    private static Set<String> entriesOf(DefaultDomain domain) {
        Set<String> entries = new HashSet<>();
        domain.getUniqueIds().forEach(id -> entries.add("uuid:" + id));
        domain.getPlayers().forEach(name -> entries.add("name:" + name.toLowerCase(Locale.ROOT)));
        domain.getGroups().forEach(group -> entries.add("group:" + group.toLowerCase(Locale.ROOT)));
        return entries;
    }

    private static DefaultDomain domainOf(Set<String> entries) {
        DefaultDomain domain = new DefaultDomain();
        for (String entry : entries) {
            if (entry.startsWith("uuid:")) {
                domain.addPlayer(UUID.fromString(entry.substring(5)));
            } else if (entry.startsWith("name:")) {
                domain.addPlayer(entry.substring(5));
            } else if (entry.startsWith("group:")) {
                domain.addGroup(entry.substring(6));
            }
        }
        return domain;
    }

    private RegionManager managerWith(String world, String regionId) {
        for (RegionManager manager : managers.get()) {
            if ((world == null || world.equalsIgnoreCase(manager.getName())) && manager.getRegion(regionId) != null) {
                return manager;
            }
        }
        return null;
    }
}
