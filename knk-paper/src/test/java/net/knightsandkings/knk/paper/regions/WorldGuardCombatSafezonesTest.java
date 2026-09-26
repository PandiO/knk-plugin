package net.knightsandkings.knk.paper.regions;

import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.paper.regions.WorldGuardCombatSafezones.RegionsAt;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** KNG-11: who counts as protected - players in an un-overridden Town/District, unless exempted. */
class WorldGuardCombatSafezonesTest {

    private final RegionDomainResolver resolver = new RegionDomainResolver();
    private final Location location = new Location(mock(World.class), 10, 64, 10);
    private final Player attacker = mock(Player.class);
    private final Player victim = mock(Player.class);

    WorldGuardCombatSafezonesTest() {
        when(victim.getLocation()).thenReturn(location);
        resolver.registerDomain(domain("town_oakhaven", "Town"));
        resolver.registerDomain(domain("district_market", "District"));
        resolver.registerDomain(domain("gate_north", "gate"));
    }

    private static DomainSnapshot domain(String regionId, String type) {
        return new DomainSnapshot(1, regionId, null, regionId, true, true, type, Set.of(), Set.of(), Set.of(), Set.of());
    }

    private WorldGuardCombatSafezones safezones(RegionsAt regions) {
        return new WorldGuardCombatSafezones(resolver, (a, v) -> false, loc -> regions);
    }

    @Test
    void aPlayerInATownIsProtected() {
        assertTrue(safezones(new RegionsAt(Set.of("town_oakhaven"), null)).isProtected(attacker, victim));
    }

    @Test
    void pvpAllowOnTheRegionLiftsTheSafezone() {
        assertFalse(safezones(new RegionsAt(Set.of("town_oakhaven", "district_market"), true)).isProtected(attacker, victim));
    }

    @Test
    void pvpDenyKeepsIt() {
        assertTrue(safezones(new RegionsAt(Set.of("district_market"), false)).isProtected(attacker, victim));
    }

    @Test
    void aGateRegionAloneIsNoSafezone() {
        assertFalse(safezones(new RegionsAt(Set.of("gate_north"), null)).isProtected(attacker, victim));
        assertTrue(safezones(new RegionsAt(Set.of("gate_north", "district_market"), null)).isProtected(attacker, victim));
    }

    @Test
    void anUncachedOrUnrelatedRegionIsNoSafezone() {
        assertFalse(safezones(new RegionsAt(Set.of("spawn_arena"), null)).isProtected(attacker, victim));
        assertFalse(safezones(new RegionsAt(Set.of(), null)).isProtected(attacker, victim));
    }

    @Test
    void mobsAreNeverProtected() {
        Zombie zombie = mock(Zombie.class);
        when(zombie.getLocation()).thenReturn(location);

        assertFalse(safezones(new RegionsAt(Set.of("town_oakhaven"), null)).isProtected(attacker, zombie));
    }

    @Test
    void anExemptedPairIsNotProtected() {
        WorldGuardCombatSafezones siegeAware = new WorldGuardCombatSafezones(resolver,
                (a, v) -> a == attacker && v == victim, loc -> new RegionsAt(Set.of("town_oakhaven"), null));

        assertFalse(siegeAware.isProtected(attacker, victim));
        assertTrue(siegeAware.isProtected(mock(Player.class), victim));
    }
}
