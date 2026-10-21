package net.knightsandkings.knk.paper.duel.ui;

import net.knightsandkings.knk.core.duel.DuelArenaSettings;
import net.knightsandkings.knk.core.duel.DuelOfferBook;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.StakeItem;
import net.knightsandkings.knk.core.duel.StakeViolation;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The editor's model (arena duels DESIGN §6.2, §6.4): gear modes, kits, own items only, coins in bounds. */
class ProposalDraftTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private final AtomicInteger refs = new AtomicInteger();
    private final ProposalDraft.Registrar registrar =
            (owner, stack, slot) -> new StakeItem(owner + ":" + refs.incrementAndGet(), stack.getType().name(), stack.getAmount());

    private static DuelArenaSettings settings(EnumSet<GearMode> modes, GearMode def, int maxStacks) {
        return new DuelArenaSettings(7, 3, 60, true, true, 1_000, maxStacks, 0, 5, modes, def, List.of(11, 12),
                30, 10, 300, 180, 5, 3);
    }

    private static ItemStack stack() {
        ItemStack s = mock(ItemStack.class);
        when(s.getType()).thenReturn(Material.DIAMOND_SWORD);
        when(s.getAmount()).thenReturn(1);
        when(s.clone()).thenReturn(s);
        return s;
    }

    @Test
    void theGearModeIsAlwaysOneTheArenaAllows() {
        ProposalDraft draft = ProposalDraft.invite(ALICE, "Alice", BOB, "Bob",
                settings(EnumSet.of(GearMode.OWN_GEAR_DROP, GearMode.KIT_CHOICE), GearMode.OWN_GEAR_DROP, 9), "Arena");
        assertEquals(GearMode.OWN_GEAR_DROP, draft.gearMode());
        for (int i = 0; i < 5; i++) {
            assertTrue(draft.cycleGearMode());
            assertTrue(EnumSet.of(GearMode.OWN_GEAR_DROP, GearMode.KIT_CHOICE).contains(draft.gearMode()));
        }
        assertFalse(draft.selectGearMode(GearMode.OWN_GEAR_KEEP));
        assertFalse(draft.selectGearMode(GearMode.FIXED_KIT));
        assertFalse(draft.selectKit(11), "no kit outside FIXED_KIT");
        assertNull(draft.contract().fixedKitId());
    }

    @Test
    void aFixedKitIsOneOfTheEligibleKits() {
        ProposalDraft draft = ProposalDraft.invite(ALICE, "Alice", BOB, "Bob",
                settings(EnumSet.of(GearMode.FIXED_KIT), GearMode.FIXED_KIT, 9), "Arena");
        assertEquals(11, draft.fixedKitId());
        assertFalse(draft.cycleGearMode(), "one mode: a fixed label");
        assertFalse(draft.selectKit(99));
        assertTrue(draft.selectKit(12));
        assertEquals(12, draft.contract().fixedKitId());
    }

    @Test
    void itemsAreOwnReferencesWithinTheStackLimit() {
        ProposalDraft draft = ProposalDraft.invite(ALICE, "Alice", BOB, "Bob",
                settings(EnumSet.of(GearMode.OWN_GEAR_KEEP), GearMode.OWN_GEAR_KEEP, 2), "Arena");
        assertTrue(draft.addOwnItem(0, stack(), s -> Optional.empty(), registrar).isEmpty());
        assertEquals(Optional.of("That stack is already in your stake."),
                draft.addOwnItem(0, stack(), s -> Optional.empty(), registrar));
        assertEquals(Optional.of("Written books can't be staked."),
                draft.addOwnItem(1, stack(), s -> Optional.of("Written books can't be staked."), registrar));
        assertTrue(draft.addOwnItem(2, stack(), s -> Optional.empty(), registrar).isEmpty());
        assertEquals(Optional.of("You can stake at most 2 stacks."), draft.addOwnItem(3, stack(), s -> Optional.empty(), registrar));
        assertEquals(2, draft.contract().challenger().items().size());
        assertTrue(draft.contract().opponent().items().isEmpty(), "only the owner adds to their half");
    }

    @Test
    void coinsStayWithinZeroAndTheMaximum() {
        ProposalDraft draft = ProposalDraft.invite(ALICE, "Alice", BOB, "Bob",
                settings(EnumSet.of(GearMode.OWN_GEAR_KEEP), GearMode.OWN_GEAR_KEEP, 9), "Arena");
        assertFalse(draft.adjustCoins(DuelSide.CHALLENGER, -10));
        assertTrue(draft.setCoins(DuelSide.OPPONENT, 5_000));
        assertEquals(1_000, draft.coins(DuelSide.OPPONENT));
        assertEquals(50, draft.fee(), "5% of the 1,000 pot");
        assertTrue(draft.violations(i -> false, i -> 0).isEmpty());
    }

    @Test
    void aCounterKeepsTheOtherSidesItemsAndMayOnlyRemoveThem() {
        DuelArenaSettings settings = settings(EnumSet.of(GearMode.OWN_GEAR_KEEP), GearMode.OWN_GEAR_KEEP, 9);
        DuelOfferBook book = new DuelOfferBook(3, 0, null);
        ProposalDraft first = ProposalDraft.invite(ALICE, "Alice", BOB, "Bob", settings, "Arena");
        first.addOwnItem(0, stack(), s -> Optional.empty(), registrar);
        var invited = book.invite(ALICE, BOB, settings, first.contract(), 0);
        assertTrue(invited.ok());

        ProposalDraft counter = ProposalDraft.counter(invited.negotiation(), "Bob", "Alice", "Arena", ref -> Optional.empty());
        assertEquals(DuelSide.OPPONENT, counter.side());
        assertEquals(2, counter.offerNumber());
        assertEquals(1, counter.items(DuelSide.CHALLENGER).size(), "pre-filled with Alice's sword");
        assertTrue(counter.removeItem(DuelSide.CHALLENGER, 0));
        List<StakeViolation> none = counter.violations(i -> false, i -> 0);
        assertTrue(none.isEmpty(), none.toString());
        assertTrue(book.counter(BOB, invited.offer().offerId(), counter.contract(), 1).ok());
    }

    @Test
    void theDesignsSwordAgainstCoinsOfferIsSentAndTheRatioIsLeftToTheApi() {
        DuelArenaSettings arena = new DuelArenaSettings(7, 3, 60, true, true, 1_000, 9, 50, 5,
                EnumSet.of(GearMode.OWN_GEAR_DROP), GearMode.OWN_GEAR_DROP, List.of(), 30, 10, 300, 180, 5, 3);
        ProposalDraft draft = ProposalDraft.invite(ALICE, "Alice", BOB, "Bob", arena, "Arena");
        draft.addOwnItem(0, stack(), s -> Optional.empty(), registrar);
        draft.setCoins(DuelSide.OPPONENT, 500);
        assertTrue(draft.violations(i -> false, i -> 0).isEmpty(), "no ratio warning the plugin can't judge");
        DuelOfferBook strict = new DuelOfferBook(3, 0, null);
        assertEquals(DuelOfferBook.Status.INVALID_CONTRACT, strict.invite(ALICE, BOB, arena, draft.contract(), 0).status());
        DuelOfferBook book = new DuelOfferBook(3, 0, null);
        assertTrue(book.invite(ALICE, BOB, DuelNegotiationUi.bookSettings(arena), draft.contract(), 0).ok());

        ProposalDraft coinsOnly = ProposalDraft.invite(ALICE, "Alice", BOB, "Bob", arena, "Arena");
        coinsOnly.setCoins(DuelSide.CHALLENGER, 1_000);
        assertEquals(StakeViolation.Code.STAKE_RATIO, coinsOnly.violations(i -> false, i -> 0).getFirst().code(),
                "coins alone are still checked here");
    }
}
