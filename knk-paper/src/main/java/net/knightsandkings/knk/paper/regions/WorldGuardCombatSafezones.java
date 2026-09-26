package net.knightsandkings.knk.paper.regions;

import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.regions.CombatSafezone;
import net.knightsandkings.knk.core.regions.RegionDomainResolver;

/**
 * {@link CombatSafezoneCheck} over WorldGuard (KNG-11): the victim is protected when they're a player
 * standing in a Town or District region and WorldGuard's {@code pvp} flag there isn't {@code allow}
 * (rule: {@link CombatSafezone}).
 * <ul>
 *   <li><b>Players only.</b> The v1/v2 safezone was WorldGuard {@code pvp=deny}, which only protects
 *       players; hitting a zombie in town with a poison sword still poisons it.</li>
 *   <li><b>Cache only.</b> Region ids resolve through {@link RegionDomainResolver#resolveRegions}, which
 *       never does I/O (this runs inside a damage event). {@code WorldGuardRegionTracker} warms that
 *       cache as players join and move, so a player's own regions are normally cached; if not, the spot
 *       counts as no safezone until it is (fail open - failing closed would make every WorldGuard
 *       region that isn't a domain a safezone).</li>
 *   <li><b>{@code combatExemption}</b> lets a mode that allows fighting inside towns (the siege
 *       minigame, whose {@code SiegeCombatListener} un-cancels allowed hits) keep its enchantments
 *       working there: return true for attacker/victim pairs it governs.</li>
 * </ul>
 */
public class WorldGuardCombatSafezones implements CombatSafezoneCheck {

    /** The WorldGuard regions at a spot and their resolved {@code pvp} flag (TRUE allow, FALSE deny, null unset). */
    public record RegionsAt(Set<String> regionIds, Boolean pvpFlag) {
        public static final RegionsAt NONE = new RegionsAt(Set.of(), null);
    }

    /**
     * Looks up {@link RegionsAt} for a location; {@link WorldGuardRegionLookup} asks WorldGuard. Kept out
     * of this class so it (and its tests) never load WorldGuard classes.
     */
    @FunctionalInterface
    public interface RegionLookup {
        RegionsAt at(Location location);
    }

    private final RegionDomainResolver regionResolver;
    private final BiPredicate<Player, Player> combatExemption;
    private final RegionLookup regionLookup;

    public WorldGuardCombatSafezones(RegionDomainResolver regionResolver, BiPredicate<Player, Player> combatExemption) {
        this(regionResolver, combatExemption, new WorldGuardRegionLookup());
    }

    WorldGuardCombatSafezones(RegionDomainResolver regionResolver, BiPredicate<Player, Player> combatExemption,
                              RegionLookup regionLookup) {
        this.regionResolver = Objects.requireNonNull(regionResolver, "regionResolver must not be null");
        this.combatExemption = combatExemption != null ? combatExemption : (attacker, victim) -> false;
        this.regionLookup = Objects.requireNonNull(regionLookup, "regionLookup must not be null");
    }

    @Override
    public boolean isProtected(Player attacker, LivingEntity victim) {
        if (!(victim instanceof Player victimPlayer)) {
            return false;
        }
        if (attacker != null && combatExemption.test(attacker, victimPlayer)) {
            return false;
        }
        Location location = victim.getLocation();
        if (location == null || location.getWorld() == null) {
            return false;
        }
        RegionsAt regions = regionLookup.at(location);
        if (regions.regionIds().isEmpty()) {
            return false;
        }
        return CombatSafezone.isSafezone(regionResolver.resolveRegions(regions.regionIds()).domains(), regions.pvpFlag());
    }
}
