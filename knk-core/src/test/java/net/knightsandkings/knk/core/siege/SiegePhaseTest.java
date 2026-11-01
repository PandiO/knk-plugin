package net.knightsandkings.knk.core.siege;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiegePhaseTest {

    @Test
    void discoveryIsBlockedFromTheHubUntilMembersAreRestored() {
        EnumSet<SiegePhase> blocking = EnumSet.noneOf(SiegePhase.class);
        for (SiegePhase phase : SiegePhase.values()) {
            if (phase.blocksDiscovery()) blocking.add(phase);
        }
        assertEquals(EnumSet.of(SiegePhase.HUB, SiegePhase.IN_PROGRESS, SiegePhase.ENDING, SiegePhase.POST_MATCH,
                SiegePhase.COOLDOWN), blocking);
    }

    /** PandiO/knk-workspace#198: members waiting at the hub after the match are still away (guards, filter). */
    @Test
    void thePostMatchWaitIsStillMatchActive_butNotJoinableOrBetweenMatches() {
        assertTrue(SiegePhase.POST_MATCH.isMatchActive());
        assertFalse(SiegePhase.POST_MATCH.isJoinable());
        assertFalse(SiegePhase.POST_MATCH.isBetweenMatches());
    }
}
