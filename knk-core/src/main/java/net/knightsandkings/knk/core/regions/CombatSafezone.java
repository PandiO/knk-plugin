package net.knightsandkings.knk.core.regions;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;

/**
 * Whether a spot is a combat safezone (KNG-11): Towns and Districts are safezones unless
 * WorldGuard's {@code pvp} flag at that spot is explicitly {@code allow}.
 * <p>
 * The override is the WorldGuard flag itself (e.g. {@code /rg flag <town-region> pvp allow}), not a
 * new Knights and Kings field: KNG-12 is making Town/District regions carry {@code pvp=deny} by
 * default, so the same flag then decides both vanilla PvP and custom-enchantment effects. The flag is
 * read the way WorldGuard resolves it (region priority, parent inheritance), so an arena region inside
 * a town with {@code pvp allow} is a combat zone.
 * <p>
 * Other domains (Structures, Gates) are not safezones of their own; a Gate inside a District is covered
 * by that District's region. Bukkit-free so it can be unit-tested; the WorldGuard lookup lives in
 * knk-paper's {@code WorldGuardCombatSafezones}.
 */
public final class CombatSafezone {

    /** Domain types (as {@link DomainSnapshot#domainType()} reports them, any case) that are safezones. */
    public static final Set<String> SAFEZONE_DOMAIN_TYPES = Set.of("town", "district");

    private CombatSafezone() {
    }

    /**
     * @param domains the Knights and Kings domains whose regions contain the spot
     * @param pvpFlag WorldGuard's resolved {@code pvp} flag there: {@code TRUE} allow, {@code FALSE} deny,
     *                {@code null} not set
     */
    public static boolean isSafezone(Collection<DomainSnapshot> domains, Boolean pvpFlag) {
        if (Boolean.TRUE.equals(pvpFlag) || domains == null) {
            return false;
        }
        return domains.stream().anyMatch(CombatSafezone::isSafezoneDomain);
    }

    public static boolean isSafezoneDomain(DomainSnapshot domain) {
        return domain != null && domain.domainType() != null
                && SAFEZONE_DOMAIN_TYPES.contains(domain.domainType().toLowerCase(Locale.ROOT));
    }
}
