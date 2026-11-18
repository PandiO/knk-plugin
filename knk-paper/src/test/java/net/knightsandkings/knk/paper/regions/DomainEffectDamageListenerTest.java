package net.knightsandkings.knk.paper.regions;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.DomainSnapshot;
import net.knightsandkings.knk.core.regions.RegionDomainResolver.RegionSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PandiO/knk-workspace#162: effect damage stopped for players in domains unless a flag allows it; sieges exempt. */
class DomainEffectDamageListenerTest {

    // Kept in a field: Location holds its world only through a WeakReference (knk-plugin CLAUDE.md).
    private final World world = mock(World.class);
    private final Location spot = new Location(world, 0, 64, 0);
    private final Player player = mock(Player.class);
    private final RegionDomainResolver resolver = mock(RegionDomainResolver.class);
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private DomainEffectDamageListener.RegionsAt regions = new DomainEffectDamageListener.RegionsAt(Set.of("domain_5"), null, null);
    private boolean inSiege;

    private final DomainEffectDamageListener listener = new DomainEffectDamageListener(enabled::get,
        DomainEffectDamageListener.DEFAULT_CAUSES, resolver, p -> inSiege, location -> regions);

    DomainEffectDamageListenerTest() {
        when(world.getName()).thenReturn("world");
        when(player.getLocation()).thenReturn(spot);
        DomainSnapshot town = new DomainSnapshot(5, "Cinix", null, "domain_5", true, true, "Town",
            Set.of(), Set.of(), Set.of(), Set.of());
        when(resolver.resolveRegions(eq("world"), anySet())).thenReturn(new RegionSnapshot(Set.of(town)));
    }

    @SuppressWarnings("removal")
    private static EntityDamageEvent damage(org.bukkit.entity.Entity victim, DamageCause cause) {
        return new EntityDamageEvent(victim, cause, mock(DamageSource.class), 2.0);
    }

    @Test
    void fireTickInATownIsStoppedAndThePlayerPutOut() {
        EntityDamageEvent event = damage(player, DamageCause.FIRE_TICK);

        listener.onDamage(event);

        assertTrue(event.isCancelled());
        verify(player).setFireTicks(0);
    }

    @Test
    void poisonInATownIsStopped() {
        EntityDamageEvent event = damage(player, DamageCause.POISON);
        listener.onDamage(event);
        assertTrue(event.isCancelled());
    }

    @Test
    void otherDamageIsUntouched() {
        EntityDamageEvent fall = damage(player, DamageCause.FALL);
        listener.onDamage(fall);
        assertFalse(fall.isCancelled());
    }

    @Test
    void outsideEveryRegionNothingChanges() {
        regions = DomainEffectDamageListener.RegionsAt.NONE;
        EntityDamageEvent event = damage(player, DamageCause.FIRE);
        listener.onDamage(event);
        assertFalse(event.isCancelled());
    }

    @Test
    void theFlagAllowsItAndPvpAllowKeepsItWhenTheFlagIsUnset() {
        regions = new DomainEffectDamageListener.RegionsAt(Set.of("domain_5"), Boolean.TRUE, Boolean.FALSE);
        EntityDamageEvent allowed = damage(player, DamageCause.FIRE_TICK);
        listener.onDamage(allowed);
        assertFalse(allowed.isCancelled());

        regions = new DomainEffectDamageListener.RegionsAt(Set.of("battleground"), null, Boolean.TRUE);
        EntityDamageEvent combatZone = damage(player, DamageCause.FIRE_TICK);
        listener.onDamage(combatZone);
        assertFalse(combatZone.isCancelled());
    }

    @Test
    void siegeMembersKeepTheSiegesRules() {
        inSiege = true;
        EntityDamageEvent event = damage(player, DamageCause.FIRE_TICK);
        listener.onDamage(event);
        assertFalse(event.isCancelled());
        verify(player, never()).setFireTicks(0);
    }

    @Test
    void mobsAndTheSwitchOff() {
        Zombie zombie = mock(Zombie.class);
        EntityDamageEvent mob = damage(zombie, DamageCause.FIRE_TICK);
        listener.onDamage(mob);
        assertFalse(mob.isCancelled());

        enabled.set(false);
        EntityDamageEvent off = damage(player, DamageCause.FIRE_TICK);
        listener.onDamage(off);
        assertFalse(off.isCancelled());
    }

    @Test
    void configuredCauses() {
        List<String> warnings = new java.util.ArrayList<>();
        assertEquals(Set.of(DamageCause.FIRE, DamageCause.POISON),
            DomainEffectDamageListener.causes(List.of("fire", " POISON ", "sunburn"), warnings::add));
        assertEquals(1, warnings.size());
        assertEquals(DomainEffectDamageListener.DEFAULT_CAUSES, DomainEffectDamageListener.causes(List.of(), warnings::add));
    }
}
