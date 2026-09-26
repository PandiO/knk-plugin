package net.knightsandkings.knk.core.siege;

import net.knightsandkings.knk.core.domain.siege.KnkSiegeGate;
import net.knightsandkings.knk.core.domain.siege.KnkSiegeScenario;
import net.knightsandkings.knk.core.domain.siege.SiegeGateState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static net.knightsandkings.knk.core.siege.SiegeTestData.player;
import static net.knightsandkings.knk.core.siege.SiegeTestData.twoTeamScenario;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Siege Phase 4: runtime locks (DESIGN §5.5). */
class SiegeRuntimeLocksTest {

    private final SiegeRuntimeLocks locks = new SiegeRuntimeLocks();
    private final KnkSiegeScenario cinix = twoTeamScenario(1, 5);
    private final KnkSiegeScenario otherCinix = twoTeamScenario(2, 5);
    private final KnkSiegeScenario elsewhere = twoTeamScenario(3, 9);

    @Test
    void noTwoLobbiesOnTheSameScenario() {
        assertTrue(locks.tryLock(1, cinix));

        assertFalse(locks.isScenarioAvailable(2, cinix));
        assertFalse(locks.tryLock(2, cinix));
        assertEquals(OptionalInt.of(1), locks.lobbyHoldingScenario(1));
    }

    @Test
    void noTwoScenariosInTheSameTown() {
        assertTrue(locks.tryLock(1, cinix));

        assertFalse(locks.tryLock(2, otherCinix));
        assertEquals(OptionalInt.of(1), locks.lobbyHoldingTown(5));
        assertTrue(locks.tryLock(2, elsewhere));
    }

    @Test
    void selectedGatesAreExclusive() {
        KnkSiegeScenario withGate = scenarioWithGate(4, 20, 13);
        KnkSiegeScenario sameGateOtherTown = scenarioWithGate(5, 21, 13);
        assertTrue(locks.tryLock(1, withGate));

        assertFalse(locks.tryLock(2, sameGateOtherTown));
        assertEquals(OptionalInt.of(1), locks.lobbyHoldingGate(13));
        assertEquals(OptionalInt.empty(), locks.lobbyHoldingGate(14));
    }

    @Test
    void relockingTheSameScenarioIsIdempotentButALobbyHoldsOneScenario() {
        assertTrue(locks.tryLock(1, cinix));
        assertTrue(locks.tryLock(1, cinix));
        assertTrue(locks.isScenarioAvailable(1, cinix), "a lobby's own lock doesn't block it");

        assertThrows(IllegalStateException.class, () -> locks.tryLock(1, elsewhere));
    }

    @Test
    void releaseFreesScenarioAndTown() {
        locks.tryLock(1, cinix);
        locks.release(1);

        assertTrue(locks.tryLock(2, otherCinix));
        assertEquals(OptionalInt.empty(), locks.lockedScenarioOf(1));
        assertEquals(OptionalInt.of(2), locks.lockedScenarioOf(2));
    }

    @Test
    void aPlayerIsInOneLobbyAtATime() {
        assertTrue(locks.tryClaimPlayer(player(1), 1));
        assertTrue(locks.tryClaimPlayer(player(1), 1));
        assertFalse(locks.tryClaimPlayer(player(1), 2));
        assertEquals(Optional.of(1), locks.lobbyOfPlayer(player(1)));

        locks.tryClaimPlayer(player(2), 1);
        locks.releasePlayers(1);
        assertTrue(locks.tryClaimPlayer(player(1), 2));
        assertTrue(locks.lobbyOfPlayer(player(2)).isEmpty());

        locks.releasePlayer(player(1));
        assertTrue(locks.lobbyOfPlayer(player(1)).isEmpty());
    }

    private static KnkSiegeScenario scenarioWithGate(int id, int townId, int gateId) {
        KnkSiegeScenario base = twoTeamScenario(id, townId);
        return SiegeTestData.scenario(id, townId, 2, base.teams(), base.objectives(), false,
                List.of(new KnkSiegeGate(gateId, "Gate", 1, SiegeGateState.CLOSED, true, false)));
    }
}
