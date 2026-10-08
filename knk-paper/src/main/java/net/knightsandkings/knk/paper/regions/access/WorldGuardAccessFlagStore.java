package net.knightsandkings.knk.paper.regions.access;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.regions.access.AccessFlagSync;
import net.knightsandkings.knk.core.regions.access.AccessFlagSync.AccessFlags;
import net.knightsandkings.knk.core.regions.access.RegionAccessRules.AccessState;

/**
 * {@link AccessFlagSync.Store} on WorldGuard: the KnK access flags of a region, in every loaded world
 * that has a region with that id. Main thread only.
 */
public final class WorldGuardAccessFlagStore implements AccessFlagSync.Store {

    private final Supplier<List<RegionManager>> managers;
    private final Set<RegionManager> dirty = new HashSet<>();

    public WorldGuardAccessFlagStore(Supplier<List<RegionManager>> managers) {
        this.managers = managers;
    }

    @Override
    public Optional<AccessFlags> read(String regionId) {
        for (RegionManager manager : managers.get()) {
            ProtectedRegion region = manager.getRegion(regionId);
            if (region != null) {
                return Optional.of(flagsOf(region));
            }
        }
        return Optional.empty();
    }

    @Override
    public void write(String regionId, AccessFlags flags) {
        for (RegionManager manager : managers.get()) {
            ProtectedRegion region = manager.getRegion(regionId);
            if (region == null) {
                continue;
            }
            region.setFlag(DomainAccessFlags.entry(), toState(flags.entry()));
            region.setFlag(DomainAccessFlags.exit(), toState(flags.exit()));
            region.setFlag(DomainAccessFlags.name(), flags.name());
            dirty.add(manager);
        }
    }

    @Override
    public Collection<String> regionsWithAccessFlags() {
        List<String> ids = new ArrayList<>();
        for (RegionManager manager : managers.get()) {
            for (ProtectedRegion region : manager.getRegions().values()) {
                if (!flagsOf(region).equals(AccessFlags.NONE)) {
                    ids.add(region.getId());
                }
            }
        }
        return ids;
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

    private static AccessFlags flagsOf(ProtectedRegion region) {
        return new AccessFlags(fromState(region.getFlag(DomainAccessFlags.entry())),
            fromState(region.getFlag(DomainAccessFlags.exit())),
            region.getFlag(DomainAccessFlags.name()));
    }

    private static AccessState fromState(StateFlag.State state) {
        if (state == null) {
            return AccessState.UNSET;
        }
        return state == StateFlag.State.DENY ? AccessState.DENY : AccessState.ALLOW;
    }

    private static StateFlag.State toState(AccessState state) {
        return switch (state) {
            case ALLOW -> StateFlag.State.ALLOW;
            case DENY -> StateFlag.State.DENY;
            case UNSET -> null;
        };
    }
}
