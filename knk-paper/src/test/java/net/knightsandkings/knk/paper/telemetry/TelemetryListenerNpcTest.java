package net.knightsandkings.knk.paper.telemetry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.paper.npc.nav.NpcEntities;

/** NAV-08 (PandiO/knk-workspace#490): no combat telemetry for hits by or on NPCs. Damage events are mocked. */
class TelemetryListenerNpcTest {

    private static Player player(boolean npc) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasMetadata(NpcEntities.METADATA_KEY)).thenReturn(npc);
        return player;
    }

    private static EntityDamageByEntityEvent hit(Player attacker, Player victim) {
        EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
        when(event.getDamager()).thenReturn(attacker);
        when(event.getEntity()).thenReturn(victim);
        return event;
    }

    @Test
    void hitsByOrOnAnNpcAreNotRecorded() {
        TelemetryEmitter emitter = mock(TelemetryEmitter.class);
        when(emitter.isEnhanced(any())).thenReturn(true);
        TelemetryListener listener = new TelemetryListener(emitter, task -> { });
        listener.onDamage(hit(player(false), player(true)));
        listener.onDamage(hit(player(true), player(false)));
        verifyNoInteractions(emitter);
    }

    @Test
    void aHitBetweenPlayersStillAsksForEnhancedTelemetry() {
        TelemetryEmitter emitter = mock(TelemetryEmitter.class);
        Player attacker = player(false);
        new TelemetryListener(emitter, task -> { }).onDamage(hit(attacker, player(false)));
        verify(emitter).isEnhanced(attacker.getUniqueId());
    }
}
