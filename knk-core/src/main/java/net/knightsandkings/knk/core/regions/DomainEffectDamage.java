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
 * Any domain type counts (Town, District, Structure, Gate, ...) except a {@code Landmark} (landmark-domain DESIGN
 * § 5 rule 5, LD03, LD12, PandiO/knk-workspace#985): a landmark never makes a spot free of effect damage and never
 * turns it back on, so a landmark in the wilderness hurts as outside it and one in a town keeps the town's rule. Siege
 * matches are exempted by the caller. Bukkit-free.
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
        if (domains == null || domains.stream().allMatch(DomainEffectDamage::isLandmark)) {
            return false;
        }
        if (effectFlag != null) {
            return !effectFlag;
        }
        return !Boolean.TRUE.equals(pvpFlag);
    }

    /** Whether {@code domain} is a landmark (its type {@code Landmark}, any case). */
    static boolean isLandmark(DomainSnapshot domain) {
        return domain != null && domain.domainType() != null && domain.domainType().trim().equalsIgnoreCase("Landmark");
    }
}
