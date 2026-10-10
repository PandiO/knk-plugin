package net.knightsandkings.knk.paper.listeners;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.teleport.CombatTagBook;
import net.knightsandkings.knk.paper.teleport.TeleportService;

/** Dying clears the dead player's teleport combat tag; the killer stays tagged. */
class CombatTagListenerTest {

    @Test
    void deathClearsTheDeadPlayersTag_only() {
        CombatTagBook tags = new CombatTagBook(10);
        TeleportService teleports = mock(TeleportService.class);
        when(teleports.combatTags()).thenReturn(tags);
        UUID victim = UUID.randomUUID();
        UUID killer = UUID.randomUUID();
        tags.tag(victim, 1_000);
        tags.tag(killer, 1_000);

        Player dead = mock(Player.class);
        when(dead.getUniqueId()).thenReturn(victim);
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(dead);
        new CombatTagListener(teleports).onDeath(death);

        assertFalse(tags.isTagged(victim, 2_000));
        assertTrue(tags.isTagged(killer, 2_000));
    }
}
