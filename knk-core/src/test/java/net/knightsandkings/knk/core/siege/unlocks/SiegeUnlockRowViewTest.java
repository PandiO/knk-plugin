package net.knightsandkings.knk.core.siege.unlocks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Siege unlocks rows and header (siege shop DESIGN § 4.2.1, A27, A28; PandiO/knk-workspace#869). */
class SiegeUnlockRowViewTest {

    private static SiegeUnlockOffer offer(SiegeUnlockOffer.State state) {
        return new SiegeUnlockOffer(4, "Trebuchet", "Indirect", 1000, 2, "Squire", state);
    }

    @Test
    void anUnlockableRowSaysThePrice_andAsksToConfirm() {
        SiegeUnlockRowView row = new SiegeUnlockRowView(offer(SiegeUnlockOffer.State.UNLOCKABLE), 1200, false);
        assertEquals(List.of("&7Unlock price: &61000 coins", "&7Minimum title: &fSquire", "", "&aUnlock for 1000 coins"), row.getLines());
        assertTrue(row.getCanUnlock());
        assertEquals("Unlock the Trebuchet for 1000 coins? Click Confirm in the menu.", row.getPurchasePrompt());
        assertEquals(4, row.getTemplateId());
    }

    @Test
    void theOtherStatesSayWhy_andGoStraightToTheAction() {
        SiegeUnlockRowView title = new SiegeUnlockRowView(offer(SiegeUnlockOffer.State.NEEDS_TITLE), 1200, false);
        assertEquals("&cNeeds title Squire", title.getLines().get(3));
        assertFalse(title.getCanUnlock());
        assertEquals("GRAY_DYE", title.getMaterial());

        SiegeUnlockRowView coins = new SiegeUnlockRowView(offer(SiegeUnlockOffer.State.NEEDS_COINS), 300, false);
        assertEquals("&cNot enough coins: 300/1000", coins.getLines().get(3));
        assertFalse(coins.getCanUnlock());

        SiegeUnlockRowView unlocked = new SiegeUnlockRowView(offer(SiegeUnlockOffer.State.UNLOCKED), 300, false);
        assertEquals("&aUnlocked", unlocked.getLines().get(3));
        assertEquals("&aTrebuchet", unlocked.getName());
        assertFalse(unlocked.getCanUnlock());
    }

    @Test
    void duringAMatchNothingIsUnlockable_A28() {
        SiegeUnlockRowView row = new SiegeUnlockRowView(offer(SiegeUnlockOffer.State.UNLOCKABLE), 1200, true);
        assertFalse(row.getCanUnlock());
        assertEquals("&c" + SiegeUnlockText.NOT_DURING_A_MATCH, row.getLines().get(3));
        assertEquals("&c" + SiegeUnlockText.NOT_DURING_A_MATCH, new SiegeUnlocksHeaderView(1200L, "Peasant", true).getMatchLine());
        assertNull(new SiegeUnlocksHeaderView(1200L, "Peasant", false).getMatchLine());
    }

    @Test
    void theHeaderShowsCoinsAndTitle() {
        assertEquals("&7Coins: &61200", new SiegeUnlocksHeaderView(1200L, "Peasant", false).getCoinsLine());
        assertEquals("&7Your title: &fPeasant", new SiegeUnlocksHeaderView(1200L, "Peasant", false).getTitleLine());
        assertEquals("&7Coins: &8loading...", new SiegeUnlocksHeaderView(null, null, false).getCoinsLine());
    }

    @Test
    void theResultMessages() {
        assertEquals("Unlocked the Ballista for 500 coins. You can buy it in siege matches now.",
                SiegeUnlockText.result(new SiegeUnlockPurchaseResult.Unlocked("Ballista", 500, false, 700)));
        assertEquals("You have already unlocked the Ballista.",
                SiegeUnlockText.result(new SiegeUnlockPurchaseResult.Unlocked("Ballista", 0, true, 700)));
        assertEquals("You need the title Squire to unlock the Trebuchet.",
                SiegeUnlockText.result(new SiegeUnlockPurchaseResult.Refused("NeedsTitle", "You need the title Squire to unlock the Trebuchet.")));
    }

    @Test
    void stateNamesFromTheApi() {
        assertEquals(SiegeUnlockOffer.State.UNLOCKED, SiegeUnlockOffer.State.fromApi("Unlocked"));
        assertEquals(SiegeUnlockOffer.State.NEEDS_COINS, SiegeUnlockOffer.State.fromApi("NeedsCoins"));
        assertEquals(SiegeUnlockOffer.State.UNLOCKABLE, SiegeUnlockOffer.State.fromApi(null));
    }
}
