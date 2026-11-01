package net.knightsandkings.knk.paper.gates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.gates.CachedGateDoor;
import net.knightsandkings.knk.core.gates.GateFireAttribution;

/** Alpha run 1 (PandiO/knk-workspace#165): statistics and the siege reward share the gate damage slot. */
class CompositeGateDamageSinkTest {

    private static final class Recording implements GateDamageSink {
        final boolean fire;
        final GateFireAttribution.Igniter igniter;
        final List<String> calls = new ArrayList<>();

        Recording(boolean fire, GateFireAttribution.Igniter igniter) {
            this.fire = fire;
            this.igniter = igniter;
        }

        @Override
        public void directDamage(CachedGateDoor gate, Entity causingEntity, double loss) {
            calls.add("direct " + loss);
        }

        @Override
        public boolean tracksFire() {
            return fire;
        }

        @Override
        public GateFireAttribution.Igniter igniterOf(Entity causingEntity) {
            return igniter;
        }

        @Override
        public void fireDamage(CachedGateDoor gate, GateFireAttribution.Igniter igniter, double loss) {
            calls.add("fire " + loss);
        }
    }

    private final CachedGateDoor gate = mock(CachedGateDoor.class);

    @Test
    void ofSkipsNullAndNone() {
        Recording one = new Recording(false, null);
        assertSame(GateDamageSink.NONE, CompositeGateDamageSink.of(null, GateDamageSink.NONE));
        assertSame(one, CompositeGateDamageSink.of(null, one, GateDamageSink.NONE));
    }

    @Test
    void directDamage_reachesEverySink_fireOnlyTheTrackingOnes() {
        GateFireAttribution.Igniter igniter = new GateFireAttribution.Igniter(UUID.randomUUID(), 7);
        Recording statistics = new Recording(false, null);
        Recording siege = new Recording(true, igniter);
        GateDamageSink sink = CompositeGateDamageSink.of(statistics, siege);

        sink.directDamage(gate, null, 12.0);
        assertTrue(sink.tracksFire());
        assertSame(igniter, sink.igniterOf(null));
        sink.fireDamage(gate, igniter, 3.0);

        assertEquals(List.of("direct 12.0"), statistics.calls);
        assertEquals(List.of("direct 12.0", "fire 3.0"), siege.calls);
    }

    @Test
    void noTrackingSink_noFire() {
        GateDamageSink sink = CompositeGateDamageSink.of(new Recording(false, null), new Recording(false, null));
        assertFalse(sink.tracksFire());
    }

    @Test
    void aFailingSinkDoesNotStopTheOthers() {
        Recording after = new Recording(true, null);
        GateDamageSink failing = new GateDamageSink() {
            @Override
            public void directDamage(CachedGateDoor gate, Entity causingEntity, double loss) {
                throw new IllegalStateException("boom");
            }
        };

        CompositeGateDamageSink.of(failing, after).directDamage(gate, null, 5.0);

        assertEquals(List.of("direct 5.0"), after.calls);
    }
}
