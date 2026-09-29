package net.knightsandkings.knk.core.domain.teleport;

import static net.knightsandkings.knk.core.teleport.TeleportTestDestinations.open;
import static net.knightsandkings.knk.core.teleport.TeleportTestDestinations.priced;
import static net.knightsandkings.knk.core.teleport.TeleportTestDestinations.titleLocked;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The server's lock state with the plugin's bypass nodes on top (DESIGN §3.7.2, §4 D6). */
class KnkTeleportDestinationTest {

    @Test
    void anOpenDestinationIsAvailable() {
        assertTrue(open(1, "Kardenna", "Town").isAvailable(false, false));
        assertEquals("town:Kardenna", open(1, "Kardenna", "Town").qualifiedName());
    }

    @Test
    void requirementLocksNeedTheRequirementsBypass() {
        KnkTeleportDestination locked = titleLocked(1, "Keep", 10, true);

        assertEquals("TitleTooLow", locked.lockCode(false, false));
        assertEquals("Reach title Knight to unlock", locked.lockReason(false, true));
        assertNull(locked.lockCode(true, false));
    }

    @Test
    void bypassingRequirementsStillNeedsTheGems() {
        KnkTeleportDestination lockedAndPoor = titleLocked(1, "Keep", 10, false);

        assertEquals("InsufficientGems", lockedAndPoor.lockCode(true, false));
        assertEquals("You don't have enough gems to teleport to this location!", lockedAndPoor.lockReason(true, false));
        assertNull(lockedAndPoor.lockCode(true, true));
    }

    @Test
    void thePriceLockNeedsTheCostBypass() {
        KnkTeleportDestination poor = priced(1, "Kardenna", 10, false);

        assertEquals("InsufficientGems", poor.lockCode(false, false));
        assertTrue(poor.isAvailable(false, true));
        assertTrue(priced(1, "Kardenna", 10, true).isAvailable(false, false));
    }
}
