package net.knightsandkings.knk.paper.siege.npc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.siege.ObjectiveState.Presence;
import net.knightsandkings.knk.core.siege.SiegeActorKind;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Siege Survival link S3 (PandiO/knk-workspace#41): mobs registered as match actors are recognised (also behind their
 * projectiles), tagged for the leftover sweep, and counted as weighted NPC presences inside a capture radius; dead
 * and gone actors are forgotten. The World mocks are fields (knk-plugin CLAUDE.md).
 */
class MatchActorRegistryTest {

    private final World world = mock(World.class);
    private final World otherWorld = mock(World.class);
    private final Map<UUID, Entity> entities = new HashMap<>();
    private final MatchActorRegistry registry = new MatchActorRegistry(new NamespacedKey("knk", "match_actor"), entities::get);

    /** A simple in-memory persistent data container for one key. */
    private static PersistentDataContainer pdc() {
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        Map<NamespacedKey, Object> values = new HashMap<>();
        org.mockito.Mockito.doAnswer(inv -> values.put(inv.getArgument(0), inv.getArgument(2)))
                .when(pdc).set(org.mockito.ArgumentMatchers.any(NamespacedKey.class),
                        org.mockito.ArgumentMatchers.<PersistentDataType<String, String>>any(), org.mockito.ArgumentMatchers.anyString());
        when(pdc.get(org.mockito.ArgumentMatchers.any(NamespacedKey.class), org.mockito.ArgumentMatchers.<PersistentDataType<String, String>>any()))
                .thenAnswer(inv -> values.get(inv.<NamespacedKey>getArgument(0)));
        return pdc;
    }

    private Zombie zombie(Location at, boolean alive) {
        Zombie zombie = mock(Zombie.class);
        UUID id = UUID.randomUUID();
        when(zombie.getUniqueId()).thenReturn(id);
        when(zombie.getLocation()).thenReturn(at);
        when(zombie.isDead()).thenReturn(!alive);
        when(zombie.isValid()).thenReturn(alive);
        PersistentDataContainer container = pdc();
        when(zombie.getPersistentDataContainer()).thenReturn(container);
        entities.put(id, zombie);
        return zombie;
    }

    @Test
    void registeringTagsTheEntityAndMakesItAnActor_alsoBehindItsProjectile() {
        Zombie zombie = zombie(new Location(world, 0, 64, 0), true);
        MatchActor actor = registry.register(zombie, 7, 2, 0.5);

        assertEquals(new MatchActor(zombie.getUniqueId(), 7, 2, 0.5), actor);
        assertTrue(registry.isActor(zombie));
        assertEquals(actor, registry.actorOf(zombie.getUniqueId()).orElseThrow());
        assertArrayEquals(new int[] {7, 2}, registry.tagOf(zombie).orElseThrow());
        assertEquals(List.of(actor), registry.actors(7));
        assertTrue(registry.actors(8).isEmpty());
        assertEquals(1, registry.size());

        Arrow arrow = mock(Arrow.class);
        when(arrow.getUniqueId()).thenReturn(UUID.randomUUID());
        when(arrow.getShooter()).thenReturn(zombie);
        assertEquals(actor, registry.actorOf(arrow).orElseThrow(), "a skeleton's arrow is the skeleton's hit");

        Zombie stranger = zombie(new Location(world, 0, 64, 0), true);
        assertFalse(registry.isActor(stranger));
        assertTrue(registry.tagOf(stranger).isEmpty());
        assertFalse(registry.isActor(null));
    }

    @Test
    void unregisterAndClearForgetActors() {
        Zombie a = zombie(new Location(world, 0, 64, 0), true);
        Zombie b = zombie(new Location(world, 0, 64, 0), true);
        Zombie c = zombie(new Location(world, 0, 64, 0), true);
        registry.register(a, 7, 2, 1);
        registry.register(b, 7, 2, 1);
        registry.register(c, 8, 2, 1);

        assertTrue(registry.unregister(a.getUniqueId()).isPresent());
        assertTrue(registry.unregister(a.getUniqueId()).isEmpty(), "already gone");
        assertEquals(1, registry.clear(7).size());
        assertTrue(registry.actors(7).isEmpty());
        assertEquals(1, registry.actors(8).size(), "the other lobby's actors stay");
        assertArrayEquals(new int[] {7, 2}, registry.tagOf(b).orElseThrow(), "the tag stays for the sweep");
    }

    @Test
    void presencesAreTheLivingActorsInsideTheRadius_withTheirWeight() {
        Location center = new Location(world, 100, 64, 100);
        Zombie near = zombie(new Location(world, 102, 64, 100), true);
        Zombie edge = zombie(new Location(world, 100, 64, 105), true);
        Zombie far = zombie(new Location(world, 120, 64, 100), true);
        Zombie dead = zombie(new Location(world, 100, 64, 101), false);
        Zombie elsewhere = zombie(new Location(otherWorld, 100, 64, 100), true);
        Zombie otherLobby = zombie(new Location(world, 100, 64, 100), true);
        Zombie gone = zombie(new Location(world, 100, 64, 100), true);
        for (Zombie z : List.of(near, edge, far, dead, elsewhere)) registry.register(z, 7, 2, 0.5);
        registry.register(otherLobby, 8, 2, 1);
        registry.register(gone, 7, 2, 1);
        entities.remove(gone.getUniqueId());

        List<Presence> present = registry.presences(7, center, 5.0);

        assertEquals(2, present.size());
        Presence first = present.stream().filter(p -> p.actorId().equals(near.getUniqueId())).findFirst().orElseThrow();
        assertEquals(SiegeActorKind.NPC, first.kind());
        assertEquals(2, first.teamId());
        assertEquals(0.5, first.weight());
        assertEquals(2.0, first.distance(), 1e-9);
        assertTrue(present.stream().anyMatch(p -> p.actorId().equals(edge.getUniqueId())), "exactly on the radius counts");
        assertFalse(registry.actorOf(dead.getUniqueId()).isPresent(), "a dead actor is forgotten");
        assertFalse(registry.actorOf(gone.getUniqueId()).isPresent(), "a vanished entity is forgotten");
        assertTrue(registry.actorOf(far.getUniqueId()).isPresent());
        assertTrue(registry.presences(7, null, 5.0).isEmpty());
        assertTrue(registry.presences(7, new Location(null, 0, 0, 0), 5.0).isEmpty());
    }

    @Test
    void aNegativeOrNonFiniteCaptureWeightIsRefused() {
        Zombie zombie = zombie(new Location(world, 0, 64, 0), true);
        assertThrows(IllegalArgumentException.class, () -> registry.register(zombie, 7, 2, -1));
        assertThrows(IllegalArgumentException.class, () -> registry.register(zombie, 7, 2, Double.NaN));
        registry.register(zombie, 7, 2, 0);
        assertFalse(registry.presences(7, new Location(world, 0, 64, 0), 5).get(0).contributes(), "weight 0: present, no pressure");
    }
}
