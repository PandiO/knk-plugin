package net.knightsandkings.knk.core.regions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;

/** PandiO/knk-workspace#162: no effect damage in domains unless a flag allows it. */
class DomainEffectDamageTest {

    private static DomainSnapshot domain(String type) {
        return new DomainSnapshot(1, "Name", null, "region_" + type, true, true, type,
                Set.of(), Set.of(), Set.of(), Set.of());
    }

    @Test
    void outsideEveryDomainNothingChanges() {
        assertFalse(DomainEffectDamage.isBlocked(List.of(), null, null));
        assertFalse(DomainEffectDamage.isBlocked(null, Boolean.FALSE, null));
    }

    @Test
    void anyDomainTypeBlocksByDefault() {
        assertTrue(DomainEffectDamage.isBlocked(List.of(domain("Town")), null, null));
        assertTrue(DomainEffectDamage.isBlocked(List.of(domain("Structure")), null, Boolean.FALSE));
        assertTrue(DomainEffectDamage.isBlocked(List.of(domain("GateStructure")), null, null));
    }

    @Test
    void theFlagDecidesWhenSet() {
        assertFalse(DomainEffectDamage.isBlocked(List.of(domain("Town")), Boolean.TRUE, Boolean.FALSE));
        assertTrue(DomainEffectDamage.isBlocked(List.of(domain("Town")), Boolean.FALSE, Boolean.TRUE));
    }

    @Test
    void aCombatZoneKeepsEffectDamageWhenTheFlagIsUnset() {
        assertFalse(DomainEffectDamage.isBlocked(List.of(domain("District")), null, Boolean.TRUE));
    }
}
