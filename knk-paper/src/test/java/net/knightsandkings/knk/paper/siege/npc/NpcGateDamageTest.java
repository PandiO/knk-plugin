package net.knightsandkings.knk.paper.siege.npc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Event;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.BlockSnapshot;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.paper.events.GateDoorDamageEvent;
import net.knightsandkings.knk.paper.gates.GateDoorHitService;
import net.knightsandkings.knk.paper.siege.npc.NpcGateDamage.Outcome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Siege Survival link S3 (PandiO/knk-workspace#41): a match NPC's hit on a closed door fires the same
 * {@link GateDoorDamageEvent} path as a player's, with cause NPC_MELEE and the factored damage; strangers, open
 * doors and non-door blocks never fire; a cancelled event (the siege rule) is reported as such.
 */
class NpcGateDamageTest {

    private final World world = mock(World.class);
    private final GateManager gateManager = new GateManager();
    private final GateDoorHitService hits = new GateDoorHitService(gateManager);
    private final Map<UUID, Entity> entities = new HashMap<>();
    private final MatchActorRegistry actors = new MatchActorRegistry(new NamespacedKey("knk", "match_actor"), entities::get);
    private final NpcGateDamage damage = new NpcGateDamage(hits, actors);
    private CachedGateDoor door;
    private MockedStatic<Bukkit> bukkit;
    private GateDoorDamageEvent fired;
    private Consumer<GateDoorDamageEvent> onEvent = event -> { };

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn("world");
        door = new CachedGateDoor(1, 10, "Main gate", "SLIDING", "VERTICAL", "PLANE_GRID",
                60, 1, new Vector(100, 64, 100), 5, 5, 3, 500.0, 500.0, true, false, false, 90, "north");
        door.setWorldName("world");
        door.setUAxis(new Vector(1, 0, 0));
        door.setVAxis(new Vector(0, 1, 0));
        door.setNAxis(new Vector(0, 0, 1));
        door.setMotionVector(new Vector(0, 3, 0));
        door.setCurrentState(AnimationState.CLOSED);
        door.addBlock(new BlockSnapshot(1, new Vector(0, 0, 0), 1, "stone", 0));
        gateManager.cacheGate(door);

        PluginManager pluginManager = mock(PluginManager.class);
        doAnswer(inv -> {
            Event event = inv.getArgument(0);
            if (event instanceof GateDoorDamageEvent damageEvent) {
                fired = damageEvent;
                onEvent.accept(damageEvent);
            }
            return null;
        }).when(pluginManager).callEvent(any(Event.class));
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private Block doorBlock() {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(100);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(100);
        return block;
    }

    private Block plainBlock() {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(7);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(7);
        return block;
    }

    private Zombie zombie(boolean registered) {
        Zombie zombie = mock(Zombie.class);
        UUID id = UUID.randomUUID();
        when(zombie.getUniqueId()).thenReturn(id);
        when(zombie.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        entities.put(id, zombie);
        if (registered) actors.register(zombie, 7, 2, 1.0);
        return zombie;
    }

    @Test
    void aRegisteredActorHitsAClosedDoorWithTheFactoredDamage() {
        Zombie zombie = zombie(true);

        assertEquals(Outcome.HIT, damage.hit(zombie, doorBlock(), 1.5));

        assertNotNull(fired);
        assertEquals(GateDoorDamageEvent.Cause.NPC_MELEE, fired.getCause());
        assertEquals(zombie, fired.getCausingEntity());
        assertEquals(door, fired.getGate());
        assertEquals(15.0, fired.getDamage(), "10 × the difficulty's gate damage factor");

        fired = null;
        assertEquals(Outcome.HIT, damage.hit(zombie, doorBlock()));
        assertEquals(10.0, fired.getDamage(), "factor 1 by default: a player's left click");
    }

    @Test
    void strangersOpenDoorsAndPlainBlocksFireNothing() {
        assertEquals(Outcome.NOT_AN_ACTOR, damage.hit(zombie(false), doorBlock(), 1));
        assertEquals(Outcome.NOT_A_DOOR, damage.hit(zombie(true), plainBlock(), 1));
        assertEquals(Outcome.NOT_A_DOOR, damage.hit(zombie(true), (Block) null, 1));
        assertEquals(Outcome.REFUSED, damage.hit(zombie(true), doorBlock(), 0), "no damage at factor 0");
        assertEquals(Outcome.REFUSED, damage.hit(zombie(true), doorBlock(), Double.NaN));

        door.setCurrentState(AnimationState.OPEN);
        assertEquals(Outcome.REFUSED, damage.hit(zombie(true), doorBlock(), 1), "only a CLOSED door takes hits");
        door.setCurrentState(AnimationState.CLOSED);
        door.setIsDestroyed(true);
        assertEquals(Outcome.REFUSED, damage.hit(zombie(true), doorBlock(), 1));
        assertNull(fired);
    }

    @Test
    void theSiegeRuleCancellingTheEventIsReported() {
        onEvent = event -> event.setCancelled(true);
        assertEquals(Outcome.CANCELLED, damage.hit(zombie(true), doorBlock(), 1));
        assertNotNull(fired);
    }

    @Test
    void damageFor() {
        assertEquals(10.0, NpcGateDamage.damageFor(1));
        assertEquals(5.0, NpcGateDamage.damageFor(0.5));
        assertEquals(0.0, NpcGateDamage.damageFor(0));
        assertEquals(0.0, NpcGateDamage.damageFor(-2));
        assertEquals(0.0, NpcGateDamage.damageFor(Double.POSITIVE_INFINITY));
    }
}
