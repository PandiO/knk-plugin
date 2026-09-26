package net.knightsandkings.knk.core.teleport;

import static net.knightsandkings.knk.core.teleport.TeleportTestDestinations.open;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.domain.teleport.KnkTeleportDestination;

/** /warp name lookup and tab completion (docs/specs/teleport/DESIGN.md §3.2, Phase 5). */
class WarpTargetsTest {

    private final KnkTeleportDestination kardenna = open(1, "Kardenna", "Town");
    private final KnkTeleportDestination marketTown = open(2, "Market", "Town");
    private final KnkTeleportDestination marketDistrict = open(3, "Market", "District");
    private final KnkTeleportDestination newHaven = open(4, "New Haven", "Town");
    private final List<KnkTeleportDestination> all = List.of(kardenna, marketTown, marketDistrict, newHaven);

    @Test
    void namesMatchIgnoringCase() {
        assertSame(kardenna, WarpTargets.resolve(all, "kARDENNA").destination());
        assertSame(newHaven, WarpTargets.resolve(all, "new haven").destination());
    }

    @Test
    void aSharedNameIsAmbiguousUntilTheTypeIsGiven() {
        WarpTargets.Match match = WarpTargets.resolve(all, "market");

        assertFalse(match.found());
        assertTrue(match.ambiguous());
        assertEquals(List.of(marketTown, marketDistrict), match.choices());
        assertSame(marketDistrict, WarpTargets.resolve(all, "district:Market").destination());
        assertSame(marketTown, WarpTargets.resolve(all, "TOWN:market").destination());
    }

    @Test
    void unknownNamesAndWrongTypesFindNothing() {
        assertSame(WarpTargets.Match.NONE, WarpTargets.resolve(all, "Nowhere"));
        assertFalse(WarpTargets.resolve(all, "structure:Kardenna").found());
        assertFalse(WarpTargets.resolve(all, "").found());
        assertFalse(WarpTargets.resolve(List.of(), "Kardenna").found());
    }

    @Test
    void aColonInAPlaceNameStillMatchesLiterally() {
        KnkTeleportDestination odd = open(9, "Spawn:Hill", "Structure");

        assertSame(odd, WarpTargets.resolve(List.of(odd), "spawn:hill").destination());
    }

    @Test
    void completionUsesTheTypeFormForSharedNamesAndSkipsNamesWithSpaces() {
        assertEquals(List.of("Kardenna", "town:Market", "district:Market"), WarpTargets.complete(all, ""));
        assertEquals(List.of("Kardenna"), WarpTargets.complete(all, "ka"));
        assertEquals(List.of("district:Market"), WarpTargets.complete(all, "dis"));
        assertEquals(List.of("town:Market", "district:Market"), WarpTargets.complete(all, "MAR"));
    }
}
