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

    /** A11 (landmark-domain § 5 rule 5, LD03, LD12, #985): a landmark never changes effect damage. */
    @Test
    void aLandmarkNeverMakesASafeZone_InTheWildernessItHurts_InATownTheTownDecides() {
        assertFalse(DomainEffectDamage.isBlocked(List.of(domain("Landmark")), null, null), "a wilderness landmark");
        assertFalse(DomainEffectDamage.isBlocked(List.of(domain("landmark")), Boolean.FALSE, null),
            "not even with a deny flag it inherited");
        assertTrue(DomainEffectDamage.isBlocked(List.of(domain("Town"), domain("Landmark")), null, null),
            "a landmark in a town: as anywhere in the town");
        assertFalse(DomainEffectDamage.isBlocked(List.of(domain("Town"), domain("Landmark")), Boolean.TRUE, null),
            "the town's allow flag still lets it through");
    }
}
