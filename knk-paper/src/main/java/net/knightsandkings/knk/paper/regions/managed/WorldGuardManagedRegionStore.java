package net.knightsandkings.knk.paper.regions.managed;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.IntegerFlag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import net.knightsandkings.knk.core.regions.managed.ActualRegionState;
import net.knightsandkings.knk.core.regions.managed.FlagState;
import net.knightsandkings.knk.core.regions.managed.ManagedRegionStore;
import net.knightsandkings.knk.core.regions.managed.RegionChange;
import net.knightsandkings.knk.core.regions.managed.RegionStoreException;

/**
 * {@link ManagedRegionStore} over WorldGuard's region managers (one per world; a region id is looked up in each world in
 * turn). Main thread only.
 * <ul>
 *   <li>{@link #apply} touches priority, parent and the named flags and nothing else, so owners, members and every
 *       other flag stay as they are; if any step fails it puts back what it had changed.</li>
 *   <li>A parent must live in the same world's manager as its child.</li>
 *   <li>{@link #persist} saves every manager that {@link #apply} changed.</li>
 * </ul>
 */
public class WorldGuardManagedRegionStore implements ManagedRegionStore {

    private final Supplier<List<RegionManager>> managers;
    private final Set<RegionManager> dirty = new LinkedHashSet<>();

    public WorldGuardManagedRegionStore(Supplier<List<RegionManager>> managers) {
        this.managers = managers;
    }

    @Override
    public Optional<ActualRegionState> read(String regionId) {
        Located located = locate(regionId);
        if (located == null) {
            return Optional.empty();
        }
        ProtectedRegion region = located.region();
        Map<String, Object> flags = new HashMap<>();
        for (Map.Entry<Flag<?>, Object> entry : region.getFlags().entrySet()) {
            flags.put(entry.getKey().getName(), toNeutral(entry.getValue()));
        }
        ProtectedRegion parent = region.getParent();
        return Optional.of(new ActualRegionState(region.getId(), parent != null ? parent.getId() : null, region.getPriority(), flags));
    }

    @Override
    public boolean supportsFlag(String flagName) {
        return WorldGuard.getInstance().getFlagRegistry().get(flagName) != null;
    }

    @Override
    public void apply(String regionId, RegionChange change) throws RegionStoreException {
        Located located = locate(regionId);
        if (located == null) {
            throw new RegionStoreException("region '" + regionId + "' does not exist");
        }
        ProtectedRegion region = located.region();
        ProtectedRegion newParent = null;
        if (change.newParentRegionId() != null) {
            newParent = located.manager().getRegion(change.newParentRegionId());
            if (newParent == null) {
                throw new RegionStoreException("parent '" + change.newParentRegionId() + "' is not in the same world as '" + regionId + "'");
            }
        }

        int oldPriority = region.getPriority();
        ProtectedRegion oldParent = region.getParent();
        Map<Flag<?>, Object> oldFlags = new HashMap<>();
        try {
            for (Map.Entry<String, Object> entry : change.flagsToSet().entrySet()) {
                Flag<?> flag = WorldGuard.getInstance().getFlagRegistry().get(entry.getKey());
                if (flag == null) {
                    throw new RegionStoreException("unknown flag '" + entry.getKey() + "'");
                }
                oldFlags.put(flag, region.getFlag(flag));
                setFlag(region, flag, entry.getValue());
            }
            if (change.newPriority() != null) {
                region.setPriority(change.newPriority());
            }
            if (newParent != null) {
                region.setParent(newParent);
            }
            dirty.add(located.manager());
        } catch (Exception e) {
            restore(region, oldPriority, oldParent, oldFlags);
            if (e instanceof RegionStoreException stored) {
                throw stored;
            }
            throw new RegionStoreException(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public void persist() throws RegionStoreException {
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
            throw new RegionStoreException(firstFailure);
        }
    }

    // ---- helpers ----

    private record Located(RegionManager manager, ProtectedRegion region) {
    }

    private Located locate(String regionId) {
        if (regionId == null || regionId.isBlank()) {
            return null;
        }
        for (RegionManager manager : managers.get()) {
            ProtectedRegion region = manager.getRegion(regionId);
            if (region != null) {
                return new Located(manager, region);
            }
        }
        return null;
    }

    private static Object toNeutral(Object value) {
        if (value instanceof StateFlag.State state) {
            return state == StateFlag.State.ALLOW ? FlagState.ALLOW : FlagState.DENY;
        }
        if (value instanceof String || value instanceof Integer) {
            return value;
        }
        return String.valueOf(value); // a flag type this code does not manage: only its presence matters
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setFlag(ProtectedRegion region, Flag<?> flag, Object value) throws RegionStoreException {
        if (flag instanceof StateFlag stateFlag && value instanceof FlagState state) {
            region.setFlag(stateFlag, state == FlagState.ALLOW ? StateFlag.State.ALLOW : StateFlag.State.DENY);
        } else if (flag instanceof StringFlag stringFlag && value instanceof String text) {
            region.setFlag(stringFlag, text);
        } else if (flag instanceof IntegerFlag integerFlag && value instanceof Integer number) {
            region.setFlag(integerFlag, number);
        } else {
            throw new RegionStoreException("flag '" + flag.getName() + "' cannot hold a " + value.getClass().getSimpleName());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void restore(ProtectedRegion region, int priority, ProtectedRegion parent, Map<Flag<?>, Object> flags) {
        try {
            region.setPriority(priority);
            region.setParent(parent);
            for (Map.Entry<Flag<?>, Object> entry : flags.entrySet()) {
                region.setFlag((Flag) entry.getKey(), entry.getValue());
            }
        } catch (Exception ignored) {
            // Putting back what was there cannot introduce a cycle; nothing more to do if it still fails.
        }
    }
}
