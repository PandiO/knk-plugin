package net.knightsandkings.knk.core.siege;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SiegePhaseTest {

    @Test
    void discoveryIsBlockedFromTheHubUntilMembersAreRestored() {
        EnumSet<SiegePhase> blocking = EnumSet.noneOf(SiegePhase.class);
        for (SiegePhase phase : SiegePhase.values()) {
            if (phase.blocksDiscovery()) blocking.add(phase);
        }
        assertEquals(EnumSet.of(SiegePhase.HUB, SiegePhase.IN_PROGRESS, SiegePhase.ENDING, SiegePhase.COOLDOWN),
                blocking);
    }
}
