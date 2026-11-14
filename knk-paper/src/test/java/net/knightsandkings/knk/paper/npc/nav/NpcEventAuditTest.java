package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class NpcEventAuditTest {

    // a field, not an inline mock: Location holds its world weakly (see knk-plugin CLAUDE.md)
    private final World world = mock(World.class);

    private static Player player(boolean npc) {
        Player player = mock(Player.class);
        when(player.hasMetadata(NpcEntities.METADATA_KEY)).thenReturn(npc);
        return player;
    }

    private PlayerMoveEvent move(Player player) {
        return new PlayerMoveEvent(player, new Location(world, 0, 64, 0), new Location(world, 1, 64, 0));
    }

    @Test
    void anNpcIsAnEntityWithTheNpcMetadata() {
        assertTrue(NpcEntities.isNpc(player(true)));
        assertFalse(NpcEntities.isNpc(player(false)));
        assertFalse(NpcEntities.isNpc(null));
    }

    @Test
    void countsOnlyEventsInvolvingAnNpc() {
        assertTrue(NpcEventAudit.involvesNpc(move(player(true))));
        assertFalse(NpcEventAudit.involvesNpc(move(player(false))));
        // a real damage event needs the server's registries; the audit only reads the entity and the damager
        EntityDamageByEntityEvent hitByNpc = mock(EntityDamageByEntityEvent.class);
        when(hitByNpc.getEntity()).thenReturn(mock(Zombie.class));
        Player npc = player(true);
        when(hitByNpc.getDamager()).thenReturn(npc);
        assertTrue(NpcEventAudit.involvesNpc(hitByNpc), "an NPC dealing damage counts");
    }

    @Test
    void theReportListsEventTypesWithCounts() {
        NpcEventAudit audit = new NpcEventAudit(mock(Plugin.class));
        audit.observe(move(player(true)));
        audit.observe(move(player(true)));
        audit.observe(move(player(false)));
        List<String> report = audit.report();
        assertTrue(report.stream().anyMatch(line -> line.contains("PlayerMoveEvent") && line.contains(" 2 ")),
            report.toString());
    }

    @Test
    void damageEventsListTheirCauses() {
        NpcEventAudit audit = new NpcEventAudit(mock(Plugin.class));
        Player npc = player(true);
        for (EntityDamageEvent.DamageCause cause : List.of(EntityDamageEvent.DamageCause.DROWNING,
            EntityDamageEvent.DamageCause.DROWNING, EntityDamageEvent.DamageCause.FALL)) {
            EntityDamageEvent damage = mock(EntityDamageEvent.class);
            when(damage.getEntity()).thenReturn(npc);
            when(damage.getCause()).thenReturn(cause);
            when(damage.getHandlers()).thenReturn(EntityDamageEvent.getHandlerList());
            audit.observe(damage);
        }
        List<String> report = audit.report();
        assertTrue(report.stream().anyMatch(line -> line.contains("causes: DROWNING 2, FALL 1")), report.toString());
        audit.reset();
        assertTrue(audit.report().stream().noneMatch(line -> line.contains("causes:")));
    }
}
