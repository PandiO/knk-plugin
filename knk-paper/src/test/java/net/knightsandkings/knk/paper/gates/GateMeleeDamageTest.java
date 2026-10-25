package net.knightsandkings.knk.paper.gates;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * GateMeleeDamage: a gate takes what the held item would do to a player - attack damage plus Sharpness.
 */
class GateMeleeDamageTest {

    private Player player;
    private ItemStack held;

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        held = mock(ItemStack.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getItemInMainHand()).thenReturn(held);
        when(player.getInventory()).thenReturn(inventory);
    }

    @Test
    void sharpnessAddsHalfALevelPlusHalf() {
        assertEquals(0.0, GateMeleeDamage.sharpnessBonus(0));
        assertEquals(1.0, GateMeleeDamage.sharpnessBonus(1));
        assertEquals(1.5, GateMeleeDamage.sharpnessBonus(2));
        assertEquals(3.0, GateMeleeDamage.sharpnessBonus(5));
        assertEquals(0.0, GateMeleeDamage.sharpnessBonus(-1));
    }

    @Test
    void aNetheriteSwordWithSharpnessFiveDoesElevenNotTheTooltipsEight() {
        GateMeleeDamage damage = new GateMeleeDamage(1.0, p -> 8.0, item -> 5);

        assertEquals(11.0, damage.damageOf(player), 1e-9);
    }

    @Test
    void anUnenchantedItemDoesItsAttackDamage() {
        GateMeleeDamage damage = new GateMeleeDamage(1.0, p -> 8.0, item -> 0);

        assertEquals(8.0, damage.damageOf(player), 1e-9);
    }

    @Test
    void anAttackDamageThatIncludesStrengthIsPassedThrough() {
        // the attribute value already includes potion effects, e.g. the Strength the custom enchantment grants
        GateMeleeDamage damage = new GateMeleeDamage(1.0, p -> 14.0, item -> 5);

        assertEquals(17.0, damage.damageOf(player), 1e-9);
    }

    @Test
    void theSharpnessLevelIsReadFromTheMainHandItem() {
        GateMeleeDamage damage = new GateMeleeDamage(1.0, p -> 1.0, item -> item == held ? 3 : 0);

        assertEquals(1.0 + 2.0, damage.damageOf(player), 1e-9);
    }

    @Test
    void theMultiplierScalesTheDamage() {
        GateMeleeDamage damage = new GateMeleeDamage(0.5, p -> 8.0, item -> 5);

        assertEquals(5.5, damage.damageOf(player), 1e-9);
    }

    @Test
    void aNegativeMultiplierIsTreatedAsZero() {
        GateMeleeDamage damage = new GateMeleeDamage(-3.0, p -> 8.0, item -> 5);

        assertEquals(0.0, damage.damageOf(player), 1e-9);
    }

    @Test
    void noPlayerMeansTheCausesDefaultApplies() {
        assertNull(new GateMeleeDamage(1.0, p -> 8.0, item -> 5).damageOf(null));
    }
}
