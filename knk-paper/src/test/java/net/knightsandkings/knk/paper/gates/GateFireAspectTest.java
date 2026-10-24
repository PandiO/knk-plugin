package net.knightsandkings.knk.paper.gates;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** GateFireAspect: a Fire Aspect weapon's melee hit sets the door block alight with a chance per level. */
class GateFireAspectTest {

    private Player player;

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getItemInMainHand()).thenReturn(mock(ItemStack.class));
        when(player.getInventory()).thenReturn(inventory);
    }

    @Test
    void theChanceGrowsWithTheLevelAndIsCappedAtOne() {
        GateFireAspect fireAspect = new GateFireAspect(0.15, item -> 0, () -> 0.5);

        assertEquals(0.0, fireAspect.chance(0), 1e-9);
        assertEquals(0.15, fireAspect.chance(1), 1e-9);
        assertEquals(0.30, fireAspect.chance(2), 1e-9);
        assertEquals(1.0, new GateFireAspect(0.6, item -> 0, () -> 0.5).chance(2), 1e-9);
    }

    @Test
    void aWeaponWithoutFireAspectNeverIgnites() {
        GateFireAspect fireAspect = new GateFireAspect(0.15, item -> 0, () -> 0.0);

        assertFalse(fireAspect.ignites(player));
    }

    @Test
    void fireAspectTwoIgnitesWhenTheRollIsUnderThirtyPercent() {
        assertTrue(new GateFireAspect(0.15, item -> 2, () -> 0.29).ignites(player));
        assertFalse(new GateFireAspect(0.15, item -> 2, () -> 0.30).ignites(player));
        assertFalse(new GateFireAspect(0.15, item -> 1, () -> 0.29).ignites(player), "level I only has 15 %");
    }

    @Test
    void aZeroChanceTurnsTheMechanicOff() {
        assertFalse(new GateFireAspect(0.0, item -> 2, () -> 0.0).ignites(player));
        assertFalse(new GateFireAspect(-1.0, item -> 2, () -> 0.0).ignites(player));
    }

    @Test
    void noPlayerNoFire() {
        assertFalse(new GateFireAspect(0.15, item -> 2, () -> 0.0).ignites(null));
    }
}
