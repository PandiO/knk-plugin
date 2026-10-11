package net.knightsandkings.knk.paper.regions.access;

import java.util.logging.Logger;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;

/**
 * The custom WorldGuard flags that carry a domain's AllowEntry/AllowExit on its region (KNG-56).
 *
 * <p>WorldGuard saves them with the region (regions file or database), so the rules are known at
 * startup, at join and during an API outage. They are ours, not WorldGuard's {@code entry}/
 * {@code exit}: those exempt region members by default and test only the highest-priority region,
 * while the domain rule is "every entered domain must allow it" (see {@code RegionAccessRules}).
 * WorldGuard itself does nothing with these flags; {@link DomainAccessHandler} enforces them.
 *
 * <p>Must be registered in {@code onLoad}, before WorldGuard loads its regions (it locks the flag
 * registry when it enables).
 */
public final class DomainAccessFlags {
    public static final String ENTRY_NAME = "knk-allow-entry";
    public static final String EXIT_NAME = "knk-allow-exit";
    public static final String NAME_NAME = "knk-domain-name";

    private static StateFlag entry;
    private static StateFlag exit;
    private static StringFlag name;

    private DomainAccessFlags() {
    }

    /** Register the flags (or adopt ones already registered, e.g. after a plugin reload). */
    public static synchronized void register(Logger logger) {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        entry = registerOrAdopt(registry, new StateFlag(ENTRY_NAME, false), StateFlag.class, logger);
        exit = registerOrAdopt(registry, new StateFlag(EXIT_NAME, false), StateFlag.class, logger);
        name = registerOrAdopt(registry, new StringFlag(NAME_NAME), StringFlag.class, logger);
    }

    public static boolean registered() {
        return entry != null && exit != null && name != null;
    }

    public static StateFlag entry() {
        return entry;
    }

    public static StateFlag exit() {
        return exit;
    }

    public static StringFlag name() {
        return name;
    }

    private static <T extends Flag<?>> T registerOrAdopt(FlagRegistry registry, T flag, Class<T> type, Logger logger) {
        try {
            registry.register(flag);
            return flag;
        } catch (FlagConflictException | IllegalStateException e) {
            Flag<?> existing = registry.get(flag.getName());
            if (type.isInstance(existing)) {
                return type.cast(existing);
            }
            if (logger != null) {
                logger.severe("[KnK Access] Could not register WorldGuard flag '" + flag.getName() + "': " + e.getMessage()
                    + " - domain AllowEntry/AllowExit will NOT be enforced");
            }
            return null;
        }
    }
}
