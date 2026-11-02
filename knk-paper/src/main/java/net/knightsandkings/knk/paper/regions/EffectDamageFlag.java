package net.knightsandkings.knk.paper.regions;

import java.util.logging.Logger;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;

/**
 * The WorldGuard flag {@code knk-effect-damage} (PandiO/knk-workspace#162): {@code allow} on a domain's region lets
 * effect damage (fire, burning, lava, poison, wither, ...) through there, {@code deny} stops it even where PvP is
 * allowed. Unset, {@code DomainEffectDamage} decides. Set it with {@code /rg flag <region> knk-effect-damage allow}.
 * WorldGuard itself does nothing with it. Must be registered in {@code onLoad}, before WorldGuard loads its regions.
 */
public final class EffectDamageFlag {

    public static final String NAME = "knk-effect-damage";

    private static volatile StateFlag flag;

    private EffectDamageFlag() {
    }

    /** Register the flag (or adopt the one already registered, e.g. after a reload). */
    public static synchronized void register(Logger logger) {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        try {
            StateFlag created = new StateFlag(NAME, false);
            registry.register(created);
            flag = created;
        } catch (FlagConflictException | IllegalStateException e) {
            Flag<?> existing = registry.get(NAME);
            if (existing instanceof StateFlag state) {
                flag = state;
            } else if (logger != null) {
                logger.warning("[KnK Regions] Could not register WorldGuard flag '" + NAME + "': " + e.getMessage()
                    + " - effect damage in domains follows the pvp flag only");
            }
        }
    }

    /** The registered flag, or null when it couldn't be registered. */
    public static StateFlag flag() {
        return flag;
    }
}
