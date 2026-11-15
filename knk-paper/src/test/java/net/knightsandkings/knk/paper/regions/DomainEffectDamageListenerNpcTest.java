package net.knightsandkings.knk.paper.regions;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.function.Predicate;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.regions.RegionDomainResolver;
import net.knightsandkings.knk.paper.npc.nav.NpcEntities;

/**
 * NAV-08 (PandiO/knk-workspace#490): domain effect damage is a player rule; an NPC is treated like a mob and never
 * reaches the region lookup. Damage events are mocked (they need the server's registries).
 */
class DomainEffectDamageListenerNpcTest {

    private static Player player(boolean npc) {
        Player player = mock(Player.class);
        when(player.hasMetadata(NpcEntities.METADATA_KEY)).thenReturn(npc);
        return player;
    }

    private static EntityDamageEvent burning(Player victim) {
        EntityDamageEvent event = mock(EntityDamageEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getCause()).thenReturn(DamageCause.FIRE_TICK);
        return event;
    }

    @SuppressWarnings("unchecked")
    @Test
    void anNpcNeverReachesTheDomainRule() {
        Predicate<Player> exempt = mock(Predicate.class);
        RegionDomainResolver resolver = mock(RegionDomainResolver.class);
        DomainEffectDamageListener.RegionLookup lookup = mock(DomainEffectDamageListener.RegionLookup.class);
        Player npc = player(true);
        EntityDamageEvent event = burning(npc);
        new DomainEffectDamageListener(() -> true, Set.of(DamageCause.FIRE_TICK), resolver, exempt, lookup)
            .onDamage(event);
        verifyNoInteractions(exempt, resolver, lookup);
        verify(event, never()).setCancelled(anyBoolean());
        verify(npc, never()).setFireTicks(0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void aPlayerStillDoes() {
        Predicate<Player> exempt = mock(Predicate.class);
        when(exempt.test(any())).thenReturn(true);
        Player player = player(false);
        new DomainEffectDamageListener(() -> true, Set.of(DamageCause.FIRE_TICK), mock(RegionDomainResolver.class),
            exempt, mock(DomainEffectDamageListener.RegionLookup.class)).onDamage(burning(player));
        verify(exempt).test(player);
    }
}
