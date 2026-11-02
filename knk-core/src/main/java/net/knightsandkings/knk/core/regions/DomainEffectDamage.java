package net.knightsandkings.knk.core.regions;

import java.util.Collection;

import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;

/**
 * Whether effect damage (fire, burning, lava, poison, wither, ...) is switched off at a spot (PandiO/knk-workspace#162,
 * developer note 2026-10-25: "effect damage such as fire damage is disabled in domains unless explicitly allowed").
 * <ol>
 *   <li>Outside every Knights and Kings domain nothing changes.</li>
 *   <li>Inside one, the WorldGuard flag {@code knk-effect-damage} decides when it is set (resolved the WorldGuard way:
 *       region priority, parent inheritance): {@code allow} lets the damage through, {@code deny} stops it.</li>
 *   <li>Unset, a spot where WorldGuard's {@code pvp} resolves to {@code allow} (a battleground, a town an admin opened
 *       for PvP; see {@link CombatSafezone}) keeps effect damage, so the fire and poison of a fight still hurt there;
 *       everywhere else in a domain it is stopped.</li>
 * </ol>
 * Any domain type counts (Town, District, Structure, Gate, ...). Siege matches are exempted by the caller. Bukkit-free.
 */
public final class DomainEffectDamage {

    private DomainEffectDamage() {
    }

    /**
     * @param domains     the Knights and Kings domains whose regions contain the spot
     * @param effectFlag  the resolved {@code knk-effect-damage} flag: TRUE allow, FALSE deny, null unset
     * @param pvpFlag     WorldGuard's resolved {@code pvp} flag: TRUE allow, FALSE deny, null unset
     * @return true when effect damage is stopped there
     */
    public static boolean isBlocked(Collection<DomainSnapshot> domains, Boolean effectFlag, Boolean pvpFlag) {
        if (domains == null || domains.isEmpty()) {
            return false;
        }
        if (effectFlag != null) {
            return !effectFlag;
        }
        return !Boolean.TRUE.equals(pvpFlag);
    }
}
