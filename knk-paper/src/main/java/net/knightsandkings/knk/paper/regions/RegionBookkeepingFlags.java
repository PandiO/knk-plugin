package net.knightsandkings.knk.paper.regions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

/**
 * The plugin's own bookkeeping flags on a region: {@code knk-creation-timestamp} (a world task's region, KNG-43) and
 * {@code knk-orphaned-since} (an orphaned domain sub-region, KNG-258). Read and written <em>by name</em>.
 *
 * <p>WorldGuard keys a region's flags by {@link Flag} object, not by name. A flag that isn't registered comes back from
 * the region file after a restart as a different object, so a lookup with a {@code new StringFlag(...)} made in code
 * finds nothing. Matching by name works either way. {@link #register} also registers both flags in {@code onLoad},
 * before WorldGuard loads its regions, so they load as real string flags.
 */
public final class RegionBookkeepingFlags {

    public static final String CREATION_TIMESTAMP = "knk-creation-timestamp";
    public static final String ORPHANED_SINCE = "knk-orphaned-since";

    private static volatile StringFlag creationTimestamp = new StringFlag(CREATION_TIMESTAMP);
    private static volatile StringFlag orphanedSince = new StringFlag(ORPHANED_SINCE);

    private RegionBookkeepingFlags() {
    }

    /** Register both flags (or adopt the ones already registered, e.g. after a reload). Call from {@code onLoad}. */
    public static synchronized void register(Logger logger) {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        creationTimestamp = registerOrAdopt(registry, creationTimestamp, logger);
        orphanedSince = registerOrAdopt(registry, orphanedSince, logger);
    }

    /** The value of the flag named {@code name} on {@code region}, or null. */
    public static String get(ProtectedRegion region, String name) {
        for (Map.Entry<Flag<?>, Object> flag : region.getFlags().entrySet()) {
            if (flag.getKey().getName().equalsIgnoreCase(name) && flag.getValue() != null) {
                return String.valueOf(flag.getValue());
            }
        }
        return null;
    }

    /** Sets (null: removes) the flag named {@code name}, replacing any copy of it under another flag object. */
    public static void set(ProtectedRegion region, String name, String value) {
        List<Flag<?>> existing = new ArrayList<>();
        for (Flag<?> flag : region.getFlags().keySet()) {
            if (flag.getName().equalsIgnoreCase(name)) {
                existing.add(flag);
            }
        }
        for (Flag<?> flag : existing) {
            region.setFlag(flag, null);
        }
        if (value != null) {
            region.setFlag(flagNamed(name), value);
        }
    }

    private static StringFlag flagNamed(String name) {
        return CREATION_TIMESTAMP.equalsIgnoreCase(name) ? creationTimestamp
            : ORPHANED_SINCE.equalsIgnoreCase(name) ? orphanedSince : new StringFlag(name);
    }

    private static StringFlag registerOrAdopt(FlagRegistry registry, StringFlag flag, Logger logger) {
        try {
            registry.register(flag);
            return flag;
        } catch (FlagConflictException | IllegalStateException e) {
            Flag<?> existing = registry.get(flag.getName());
            if (existing instanceof StringFlag registered) {
                return registered;
            }
            if (logger != null) {
                logger.warning("[KnK Regions] Could not register WorldGuard flag '" + flag.getName() + "': " + e.getMessage()
                    + " - it is still read and written by name");
            }
            return flag;
        }
    }
}
