package net.knightsandkings.knk.core.domain.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.knightsandkings.knk.core.teleport.TeleportKind;

/** Permission-group teleport prices on the plugin side (Linear KNG-41): labels, lock codes, the policy. */
class TeleportGroupPricesTest {

    private static KnkTeleportDestination warp(int gems, int coins, int xp, boolean canAfford, String lockCode, String lockReason,
                                               boolean requirementsMet) {
        return new KnkTeleportDestination(1, "Kardenna", "Town", "world", 0, 64, 0, 0f, 0f, gems, null, null, false,
            requirementsMet && canAfford, requirementsMet, canAfford, lockCode, lockReason, coins, xp);
    }

    @Test
    void priceLabelListsEveryCurrency_SingularForOne() {
        assertEquals("free", warp(0, 0, 0, true, null, null, true).priceLabel());
        assertEquals("10 gems", warp(10, 0, 0, true, null, null, true).priceLabel());
        assertEquals("100 coins and 1 gem", warp(1, 100, 0, true, null, null, true).priceLabel());
        assertEquals("1 coin, 2 gems and 50 XP", warp(2, 1, 50, true, null, null, true).priceLabel());
        assertFalse(warp(0, 0, 0, true, null, null, true).hasPrice());
        assertTrue(warp(0, 0, 5, true, null, null, true).hasPrice());
    }

    @Test
    void theServersInsufficientCodeIsKept() {
        KnkTeleportDestination shortOfXp = warp(0, 0, 50, false, "InsufficientExperience",
            "You don't have enough XP to teleport to this location!", true);

        assertEquals("InsufficientExperience", shortOfXp.lockCode(false, false));
        assertEquals("You don't have enough XP to teleport to this location!", shortOfXp.lockReason(false, false));
        assertNull(shortOfXp.lockCode(false, true));
    }

    @Test
    void bypassingARequirement_ThenShortOfAComboPrice_NamesThePrice() {
        KnkTeleportDestination lockedAndPoor = warp(1, 100, 0, false, "TitleTooLow", "Reach title Knight to unlock", false);

        assertEquals("InsufficientGems", lockedAndPoor.lockCode(true, false));
        assertEquals("You can't afford the 100 coins and 1 gem this teleport costs!", lockedAndPoor.lockReason(true, false));
    }

    @Test
    void policyKindsDecideWhetherATeleportCostsAnything() {
        KnkTeleportPolicy.Kind none = KnkTeleportPolicy.Kind.NONE;
        KnkTeleportPolicy.Kind half = new KnkTeleportPolicy.Kind("Multiplier", 0.5, null, null, null, null);
        KnkTeleportPolicy.Kind zero = new KnkTeleportPolicy.Kind("Multiplier", 0d, null, null, null, null);
        KnkTeleportPolicy.Kind freeFixed = new KnkTeleportPolicy.Kind("Fixed", null, 0, null, null, 7);
        KnkTeleportPolicy.Kind combo = new KnkTeleportPolicy.Kind("fixed", null, 10, 1, 0, null);

        assertFalse(none.costsAnything(0));
        assertTrue(none.costsAnything(250));
        assertEquals("250 coins", none.priceLabel(250));
        assertTrue(half.costsAnything(250));
        assertEquals("125 coins", half.priceLabel(250));
        assertEquals("1 coin", half.priceLabel(1)); // 0.5 rounds up, like the server
        assertFalse(zero.costsAnything(250));
        assertFalse(freeFixed.costsAnything(250));
        assertEquals(7, freeFixed.cooldown().getAsInt());
        assertTrue(combo.costsAnything(0));
        assertEquals("10 coins and 1 gem", combo.priceLabel(0));
        assertTrue(none.cooldown().isEmpty());
    }

    @Test
    void policyOfAKind_StaffAndBackAreNeverGroupPriced() {
        KnkTeleportPolicy.Kind spawn = new KnkTeleportPolicy.Kind("Fixed", null, 30, null, null, 5);
        KnkTeleportPolicy policy = new KnkTeleportPolicy(null, null, spawn);

        assertSame(spawn, policy.of(TeleportKind.SPAWN));
        assertSame(KnkTeleportPolicy.Kind.NONE, policy.of(TeleportKind.REQUEST));
        assertSame(KnkTeleportPolicy.Kind.NONE, policy.of(TeleportKind.STAFF));
        assertSame(KnkTeleportPolicy.Kind.NONE, policy.of(TeleportKind.BACK));
    }

    @Test
    void chargeResultsCarryTheirPayments() {
        TeleportChargeResult legacy = TeleportChargeResult.allowed("Gems", 10, 40, false, null);
        TeleportChargeResult free = TeleportChargeResult.allowed("Coins", List.of(), 10, false, null);
        TeleportChargeResult combo = TeleportChargeResult.allowed("Coins",
            List.of(new TeleportPayment("Coins", 100, 900), new TeleportPayment("Gems", 1, 49)), 900, false, null);

        assertEquals(List.of(new TeleportPayment("Gems", 10, 40)), legacy.payments());
        assertTrue(legacy.paid());
        assertFalse(free.paid());
        assertEquals(("Coins"), combo.currency());
        assertEquals(100, combo.charged());
        assertEquals("100 coins and 1 gem", TeleportPayment.describeAmounts(combo.payments()));
        assertEquals("900 coins and 49 gems", TeleportPayment.describeBalances(combo.payments()));
        assertTrue(TeleportChargeResult.refused("X", "no").payments().isEmpty());
    }
}
