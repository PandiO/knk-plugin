package net.knightsandkings.knk.core.regions;

import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** KNG-11: Towns and Districts are combat safezones unless WorldGuard's pvp flag is explicitly allow. */
class CombatSafezoneTest {

    private static DomainSnapshot domain(String type) {
        return new DomainSnapshot(1, "Name", null, "region_" + type, true, true, type,
                Set.of(), Set.of(), Set.of(), Set.of());
    }

    @Test
    void townsAndDistrictsAreSafezonesByDefault() {
        assertTrue(CombatSafezone.isSafezone(List.of(domain("Town")), null));
        assertTrue(CombatSafezone.isSafezone(List.of(domain("district")), null));
    }

    @Test
    void anExplicitPvpDenyKeepsItASafezone() {
        assertTrue(CombatSafezone.isSafezone(List.of(domain("Town")), false));
    }

    @Test
    void pvpAllowOverridesTheSafezone() {
        assertFalse(CombatSafezone.isSafezone(List.of(domain("Town")), true));
        assertFalse(CombatSafezone.isSafezone(List.of(domain("District"), domain("Town")), true));
    }

    @Test
    void structuresAndGatesAreNotSafezonesOnTheirOwn() {
        assertFalse(CombatSafezone.isSafezone(List.of(domain("Structure")), null));
        assertFalse(CombatSafezone.isSafezone(List.of(domain("gate")), null));
    }

    @Test
    void aGateInsideADistrictIsCoveredByTheDistrict() {
        assertTrue(CombatSafezone.isSafezone(List.of(domain("gate"), domain("District")), null));
    }

    @Test
    void outsideAnyDomainIsNoSafezone() {
        assertFalse(CombatSafezone.isSafezone(List.of(), null));
        assertFalse(CombatSafezone.isSafezone(null, false));
        assertFalse(CombatSafezone.isSafezone(List.of(domain(null)), null));
    }
}
