package net.knightsandkings.knk.paper.npc.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import net.knightsandkings.knk.core.npc.nav.NpcMover;

/**
 * The exemption set-up per NPC (implementation plan 4c.4 item 7.2, link M7): an NPC that players never push is exempt
 * from every online player, a player who joins later included and one who quits taken out; with players-push-npcs on
 * only the players the service exempts, and only until it restores them; a respawned entity is enrolled afresh.
 */
class BukkitCollisionPolicyTest {

    private ServerMock server;
    private WorldMock world;
    private Plugin plugin;
    private final Map<Integer, Entity> entities = new HashMap<>();
    private BukkitCollisionPolicy policy;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("w");
        plugin = MockBukkit.createMockPlugin("KnightsAndKings");
        policy = new BukkitCollisionPolicy(m -> Optional.ofNullable(entities.get(m.id()))).start(plugin);
    }

    @AfterEach
    void tearDown() {
        policy.stop();
        MockBukkit.unmock();
    }

    private NpcMover npc(int id) {
        NpcMover mover = mock(NpcMover.class);
        when(mover.id()).thenReturn(id);
        entities.put(id, world.spawn(new Location(world, id, 65, 0), Zombie.class));
        return mover;
    }

    private LivingEntity entity(int id) {
        return (LivingEntity) entities.get(id);
    }

    @Test
    void playersNeverPushAnNpcTheyAreAllExemptFrom() {
        PlayerMock early = server.addPlayer("Early");
        NpcMover guard = npc(1);
        policy.enroll(guard, false);
        assertTrue(entity(1).isCollidable(), "it still collides with other NPCs");
        assertTrue(entity(1).getCollidableExemptions().contains(early.getUniqueId()));
        PlayerMock late = server.addPlayer("Late");
        assertTrue(policy.exempt(1, late.getUniqueId()), "a player who joins later is exempt too");
        late.disconnect();
        assertFalse(entity(1).getCollidableExemptions().contains(late.getUniqueId()), "taken out when they quit");
        policy.exempt(1, early.getUniqueId().toString(), false);
        assertTrue(policy.exempt(1, early.getUniqueId()), "restoring a player who never pushes keeps them exempt");
    }

    @Test
    void withPlayersPushingOnlyTheExemptedPlayerPassesThrough() {
        PlayerMock steve = server.addPlayer("Steve");
        PlayerMock alex = server.addPlayer("Alex");
        NpcMover guard = npc(1);
        policy.enroll(guard, true);
        assertTrue(entity(1).getCollidableExemptions().isEmpty(), "players bump it");
        policy.exempt(1, steve.getUniqueId().toString(), true);
        assertTrue(policy.exempt(1, steve.getUniqueId()));
        assertFalse(policy.exempt(1, alex.getUniqueId()));
        server.addPlayer("Late");
        assertEquals(1, entity(1).getCollidableExemptions().size(), "a joining player is not exempt");
        policy.exempt(1, steve.getUniqueId().toString(), false);
        assertFalse(policy.exempt(1, steve.getUniqueId()), "restored after its time");
    }

    @Test
    void aRespawnedEntityIsEnrolledAfreshAndAReleasedNpcIsLeftAlone() {
        server.addPlayer("Early");
        NpcMover guard = npc(1);
        policy.enroll(guard, false);
        entities.put(1, world.spawn(new Location(world, 1, 65, 0), Zombie.class)); // Citizens made a new entity
        assertTrue(entity(1).getCollidableExemptions().isEmpty());
        policy.enroll(guard, false);
        assertEquals(1, entity(1).getCollidableExemptions().size());
        policy.release(1);
        assertEquals(0, policy.enrolledCount());
        server.addPlayer("Late");
        assertEquals(1, entity(1).getCollidableExemptions().size(), "released: joins no longer reach it");
    }

    @Test
    void unknownPlayerIdsAndDespawnedNpcsAreIgnored() {
        NpcMover guard = npc(1);
        policy.enroll(guard, true);
        policy.exempt(1, "not-a-uuid", true);
        policy.exempt(2, java.util.UUID.randomUUID().toString(), true);
        entities.remove(1);
        policy.exempt(1, java.util.UUID.randomUUID().toString(), true);
        policy.enroll(guard, false);
        assertEquals(1, policy.enrolledCount());
    }
}
