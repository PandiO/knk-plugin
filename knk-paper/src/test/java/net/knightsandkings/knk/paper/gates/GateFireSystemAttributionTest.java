package net.knightsandkings.knk.paper.gates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.knightsandkings.knk.core.domain.gates.AnimationState;
import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateFireAttribution;
import net.knightsandkings.knk.core.gates.GateManager;
import net.knightsandkings.knk.api.GateDoorsApi;

/**
 * KNG-34 fire attribution in the real fire system (DESIGN.md §F.8, plan §8 link 4 criterion 4): each
 * tick's effective loss is credited per igniter, and the gate's HP sequence is identical with the
 * statistics sink on and off. Uses the real HealthSystem on its non-lethal continuous path (no server
 * needed); the World mock is held in a field (knk-plugin CLAUDE.md).
 */
class GateFireSystemAttributionTest {

    private static final long FIRE_DURATION_MILLIS = 60_000L;
    private static final double DAMAGE_PER_BLOCK_PER_TICK = 2.0;

    private final World world = mock(World.class);
    private MockedStatic<Bukkit> bukkit;

    /** Records what the gate code reports; igniter = the player entity. */
    private static final class RecordingSink implements GateDamageSink {
        final Map<UUID, Double> fire = new HashMap<>();

        @Override
        public boolean tracksFire() {
            return true;
        }

        @Override
        public GateFireAttribution.Igniter igniterOf(Entity causingEntity) {
            return causingEntity instanceof Player player ? new GateFireAttribution.Igniter(player.getUniqueId(), 1) : null;
        }

        @Override
        public void fireDamage(CachedGateDoor gate, GateFireAttribution.Igniter igniter, double loss) {
            fire.merge(igniter.playerId(), loss, Double::sum);
        }
    }

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getWorld(anyString())).thenReturn(world);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private static CachedGateDoor gate(int id, double health) {
        CachedGateDoor gate = new CachedGateDoor(
            id, id, "Gate" + id, "SLIDING", "VERTICAL", "PLANE_GRID",
            60, 1, new Vector(100, 64, 100), 5, 5, 3,
            health, 500.0, true, false, false, 90, "north"
        );
        gate.setWorldName("world");
        gate.setCurrentState(AnimationState.CLOSED);
        return gate;
    }

    private Block blockAt(int x, int y, int z) {
        Block block = mock(Block.class);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        // No world: igniteBlock skips its sound/particle effect (Sound needs a server registry); the
        // bookkeeping is what this test covers.
        when(block.getWorld()).thenReturn(null);
        return block;
    }

    private static Player player() {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    private static HealthSystem healthSystem(GateManager gates) {
        return new HealthSystem(mock(GateDoorsApi.class), mock(Plugin.class), null, gates);
    }

    /** Runs the same ignitions and ticks on a fresh gate; returns the HP after each tick. */
    private List<Double> run(GateDamageSink sink, Player alice, Player bob, int ticks) {
        GateManager gates = new GateManager();
        CachedGateDoor gate = gate(1, 100.0);
        gates.cacheGate(gate);
        GateFireSystem fire = new GateFireSystem(healthSystem(gates), gates, FIRE_DURATION_MILLIS, DAMAGE_PER_BLOCK_PER_TICK);
        fire.setDamageSink(sink);

        fire.igniteBlock(gate, blockAt(100, 64, 100), alice);
        fire.igniteBlock(gate, blockAt(101, 64, 100), alice);
        fire.igniteBlock(gate, blockAt(102, 64, 100), bob);
        fire.igniteBlock(gate, blockAt(103, 64, 100), null);
        List<Double> hp = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            fire.tick();
            hp.add(gate.getHealthCurrent());
        }
        return hp;
    }

    @Test
    void hpSequencesAreIdenticalWithTheSinkOnAndOff() {
        Player alice = player();
        Player bob = player();

        List<Double> off = run(GateDamageSink.NONE, alice, bob, 10);
        List<Double> on = run(new RecordingSink(), alice, bob, 10);

        assertEquals(off, on);
        assertEquals(List.of(92.0, 84.0, 76.0, 68.0, 60.0, 52.0, 44.0, 36.0, 28.0, 20.0), on);
    }

    @Test
    void eachTicksLossIsCreditedPerIgniterAndTheUnattributedShareToNobody() {
        Player alice = player();
        Player bob = player();
        RecordingSink sink = new RecordingSink();

        run(sink, alice, bob, 5);

        // 4 blocks x 2 HP x 5 ticks = 40 HP: alice 2 blocks, bob 1, one unattributed
        assertEquals(20.0, sink.fire.get(alice.getUniqueId()), 1e-9);
        assertEquals(10.0, sink.fire.get(bob.getUniqueId()), 1e-9);
        assertEquals(2, sink.fire.size());
    }

    @Test
    void anInvincibleGateCreditsNothing() {
        GateManager gates = new GateManager();
        CachedGateDoor gate = gate(2, 100.0);
        gate.setIsInvincible(true);
        gates.cacheGate(gate);
        RecordingSink sink = new RecordingSink();
        GateFireSystem fire = new GateFireSystem(healthSystem(gates), gates, FIRE_DURATION_MILLIS, DAMAGE_PER_BLOCK_PER_TICK);
        fire.setDamageSink(sink);
        fire.igniteBlock(gate, blockAt(100, 64, 100), player());

        fire.tick();

        assertEquals(100.0, gate.getHealthCurrent());
        assertTrue(sink.fire.isEmpty());
    }

    @Test
    void reIgnitingHandsTheBlockToTheNewestIgniterAndAnOpenedGateForgetsThem() {
        GateManager gates = new GateManager();
        CachedGateDoor gate = gate(3, 100.0);
        gates.cacheGate(gate);
        RecordingSink sink = new RecordingSink();
        GateFireSystem fire = new GateFireSystem(healthSystem(gates), gates, FIRE_DURATION_MILLIS, DAMAGE_PER_BLOCK_PER_TICK);
        fire.setDamageSink(sink);
        Player alice = player();
        Player bob = player();
        Block door = blockAt(100, 64, 100);

        fire.igniteBlock(gate, door, alice);
        fire.igniteBlock(gate, door, bob);
        fire.tick();
        assertEquals(Map.of(bob.getUniqueId(), 2.0), sink.fire);

        gate.setCurrentState(AnimationState.OPENING);
        fire.tick();
        assertEquals(0, fire.attribution().attributedBlocks(gate.getId()));
    }

    @Test
    void withoutASinkNoIgnitersAreKept() {
        GateManager gates = new GateManager();
        CachedGateDoor gate = gate(4, 100.0);
        gates.cacheGate(gate);
        GateFireSystem fire = new GateFireSystem(healthSystem(gates), gates, FIRE_DURATION_MILLIS, DAMAGE_PER_BLOCK_PER_TICK);

        fire.igniteBlock(gate, blockAt(100, 64, 100), player());

        assertEquals(0, fire.attribution().attributedBlocks(gate.getId()));
        assertTrue(gate.isOnFire());
    }
}
