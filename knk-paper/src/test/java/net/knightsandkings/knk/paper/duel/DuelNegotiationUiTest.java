package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.domain.duels.DuelRefusedException;
import net.knightsandkings.knk.core.duel.DuelNegotiation;
import net.knightsandkings.knk.core.duel.DuelOffer;
import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.paper.duel.ui.DuelInviteToggles;
import net.knightsandkings.knk.paper.duel.ui.DuelKitPicker;
import net.knightsandkings.knk.paper.duel.ui.DuelOfferView;
import net.knightsandkings.knk.paper.duel.ui.DuelProposalEditor;
import net.knightsandkings.knk.paper.duel.ui.ProposalDraft;
import net.knightsandkings.knk.paper.listeners.DuelMenuListener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Arena duels P5 (KNG-219): the proposal editor, the offer view, the chat offers and the kit picker on top of P4's
 * real runtime ({@link DuelHarness}). Plan Phase 5 tests: editor click handling, a moved item refused at lock-in
 * with nothing taken, an API refusal handing the items back, and a gear mode the arena doesn't allow.
 */
class DuelNegotiationUiTest {

    @TempDir
    Path dir;
    DuelUiHarness u;
    DuelHarness h;

    @BeforeEach
    void setUp() {
        u = new DuelUiHarness(dir);
        h = u.h;
    }

    private DuelProposalEditor.Holder openEditor() {
        u.ui.invite(h.alice.player(), h.bob.player(), "Bob", false);
        DuelProposalEditor.Holder editor = u.editorOf(h.alice);
        assertNotNull(editor, "the editor opened");
        return editor;
    }

    private DuelOffer currentOfferOf(DuelTestItems.TestPlayer inviter) {
        DuelNegotiation n = h.service.offers().negotiationsOf(inviter.id()).getFirst();
        return n.currentOffer();
    }

    // ==================== Editor click handling ====================

    @Test
    void aClickOnYourOwnInventoryStakesAReferenceAndNothingMoves() {
        ItemStack sword = h.items.item("sword");
        h.alice.give(sword);
        DuelProposalEditor.Holder editor = openEditor();

        u.clickBottom(h.alice, 0);

        List<ProposalDraft.Item> staked = editor.draft().items(DuelSide.CHALLENGER);
        assertEquals(1, staked.size());
        assertEquals(0, staked.getFirst().slot());
        assertSame(sword, h.alice.contents[0], "the stack stays where it is");
        verify(h.alice.player().getInventory(), never()).setItem(anyInt(), any());
        assertEquals("stake sword", u.iconAt(h.alice, DuelProposalEditor.LEFT_ITEMS[0]));

        u.clickBottom(h.alice, 0);
        assertEquals(1, editor.draft().items(DuelSide.CHALLENGER).size(), "the same stack can't be staked twice");

        u.clickTop(h.alice, DuelProposalEditor.LEFT_ITEMS[0]);
        assertTrue(editor.draft().items(DuelSide.CHALLENGER).isEmpty(), "a click on the icon removes it");
        assertSame(sword, h.alice.contents[0]);
    }

    @Test
    void aLoanedOrBlockedItemIsRefusedWhenClicked() {
        ItemStack loaned = h.items.item("loaned");
        h.loans.add(loaned);
        h.alice.give(loaned);
        DuelProposalEditor.Holder editor = openEditor();

        u.clickBottom(h.alice, 0);

        assertTrue(editor.draft().items(DuelSide.CHALLENGER).isEmpty());
        assertTrue(DuelUiHarness.chat(h.alice).contains("loaned"), DuelUiHarness.allChat(h.alice));
    }

    @Test
    void theCoinButtonsAndTheAnvilSetBothSidesWithinTheArenasMaximum() {
        DuelProposalEditor.Holder editor = openEditor();
        ProposalDraft draft = editor.draft();

        u.clickTop(h.alice, DuelProposalEditor.LEFT_COINS[3]); // +100
        u.clickTop(h.alice, DuelProposalEditor.LEFT_COINS[3]);
        u.clickTop(h.alice, DuelProposalEditor.LEFT_COINS[1]); // -10
        u.clickTop(h.alice, DuelProposalEditor.RIGHT_COINS[2]); // +10 for Bob
        assertEquals(190, draft.coins(DuelSide.CHALLENGER));
        assertEquals(10, draft.coins(DuelSide.OPPONENT));
        for (int i = 0; i < 5; i++) u.clickTop(h.alice, DuelProposalEditor.LEFT_COINS[0]); // -100
        assertEquals(0, draft.coins(DuelSide.CHALLENGER), "never below 0");

        u.clickTop(h.alice, DuelProposalEditor.YOUR_AMOUNT);
        assertNotNull(u.anvilDone, "the anvil opened");
        u.open.remove(h.alice.id()); // the anvil replaced the editor
        u.ui.editorClosed(h.alice.player(), editor);
        u.anvilDone.accept("12,345");
        assertEquals(10_000, draft.coins(DuelSide.CHALLENGER), "capped at MaxCoinStake");
        assertSame(draft, u.editorOf(h.alice).draft(), "the editor came back with the same draft");
        assertTrue(u.iconAt(h.alice, DuelProposalEditor.YOUR_AMOUNT).startsWith("Your coins: 10,000"));
        assertTrue(u.iconAt(h.alice, DuelProposalEditor.YOUR_AMOUNT).contains("Your balance: 5,000"));
    }

    @Test
    void theGearModeButtonCyclesOnlyThroughTheModesTheArenaAllows() {
        h.arena = DuelHarness.arena(true, false, true, false);
        DuelProposalEditor.Holder editor = openEditor();
        ProposalDraft draft = editor.draft();
        assertEquals(List.of(GearMode.OWN_GEAR_KEEP, GearMode.FIXED_KIT), draft.allowedModes());

        u.clickTop(h.alice, DuelProposalEditor.GEAR);
        assertEquals(GearMode.FIXED_KIT, draft.gearMode());
        assertEquals(DuelHarness.KIT_A, draft.fixedKitId(), "the first eligible kit by default");
        u.clickTop(h.alice, DuelProposalEditor.GEAR);
        assertEquals(GearMode.OWN_GEAR_KEEP, draft.gearMode());
        assertNull(draft.fixedKitId());

        assertFalse(draft.selectGearMode(GearMode.OWN_GEAR_DROP), "drop mode isn't allowed here");
        assertFalse(draft.selectGearMode(GearMode.KIT_CHOICE));
        assertEquals(GearMode.OWN_GEAR_KEEP, draft.gearMode());
    }

    @Test
    void anArenaWithOneGearModeShowsAFixedLabel() {
        h.arena = DuelHarness.arena(true, false, false, false);
        DuelProposalEditor.Holder editor = openEditor();

        u.clickTop(h.alice, DuelProposalEditor.GEAR);

        assertEquals(GearMode.OWN_GEAR_KEEP, editor.draft().gearMode());
        assertTrue(u.iconAt(h.alice, DuelProposalEditor.GEAR).contains("The only gear mode this arena allows"));
        assertTrue(DuelUiHarness.chat(h.alice).contains("This arena only allows Own gear (keep)."));
    }

    @Test
    void theOfferBookRefusesAGearModeTheArenaDoesntAllow() {
        h.arena = DuelHarness.arena(true, false, false, false);
        DuelProposalEditor.Holder editor = openEditor();
        ProposalDraft draft = editor.draft();

        var outcome = h.service.offers().invite(h.alice.id(), h.bob.id(), draft.settings(),
                h.contract(GearMode.OWN_GEAR_DROP, null, null, null), h.tick);

        assertEquals(net.knightsandkings.knk.core.duel.DuelOfferBook.Status.INVALID_CONTRACT, outcome.status());
    }

    @Test
    void theKitButtonOpensThePickerForAFixedKitDuel() {
        h.arena = DuelHarness.arena(true, false, true, false);
        DuelProposalEditor.Holder editor = openEditor();
        u.clickTop(h.alice, DuelProposalEditor.KIT);
        assertInstanceOf(DuelProposalEditor.Holder.class, u.screen(h.alice), "no kit button in own-gear mode");

        u.clickTop(h.alice, DuelProposalEditor.GEAR); // FIXED_KIT
        u.clickTop(h.alice, DuelProposalEditor.KIT);
        DuelKitPicker.Holder picker = assertInstanceOf(DuelKitPicker.Holder.class, u.screen(h.alice));
        assertTrue(u.iconAt(h.alice, DuelKitPicker.FIRST_KIT + 1).startsWith("Archer"));
        assertTrue(u.iconAt(h.alice, DuelKitPicker.FIRST_KIT).contains("Iron Sword"), "the kit's items are previewed");

        u.ui.kitPicker().click(h.alice.player(), picker, DuelKitPicker.FIRST_KIT + 1);

        assertEquals(DuelHarness.KIT_B, editor.draft().fixedKitId());
        assertSame(editor.draft(), u.editorOf(h.alice).draft(), "back in the editor");
    }

    @Test
    void theFeeAndTheApisEligibilityWarningsAreShown() {
        u.check = new net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.CheckResult(false, true, 10, 5,
                List.of(new net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Refusal("PairCooldown",
                        "You two dueled too recently.")));
        openEditor();
        assertTrue(u.iconAt(h.alice, DuelProposalEditor.WARNINGS).contains("friendly duel"), "no checks for a friendly duel");

        u.clickTop(h.alice, DuelProposalEditor.LEFT_COINS[3]);
        u.clickTop(h.alice, DuelProposalEditor.RIGHT_COINS[3]);

        assertTrue(u.iconAt(h.alice, DuelProposalEditor.FEE).contains("10 coins"), u.iconAt(h.alice, DuelProposalEditor.FEE));
        assertTrue(u.iconAt(h.alice, DuelProposalEditor.WARNINGS).startsWith("Warnings"));
        assertTrue(u.iconAt(h.alice, DuelProposalEditor.WARNINGS).contains("You two dueled too recently."));
    }

    @Test
    void everyClickAndDragInADuelMenuIsCancelled() {
        ItemStack sword = h.items.item("sword");
        h.alice.give(sword);
        DuelProposalEditor.Holder editor = openEditor();
        DuelMenuListener listener = new DuelMenuListener(u.ui);
        Inventory top = editor.getInventory();
        Inventory bottom = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        when(view.getBottomInventory()).thenReturn(bottom);

        for (org.bukkit.event.inventory.ClickType type : org.bukkit.event.inventory.ClickType.values()) {
            for (Inventory clicked : List.of(top, bottom)) {
                InventoryClickEvent event = mock(InventoryClickEvent.class);
                when(event.getView()).thenReturn(view);
                when(event.getClickedInventory()).thenReturn(clicked);
                when(event.getClick()).thenReturn(type);
                when(event.getWhoClicked()).thenReturn(h.alice.player());
                when(event.getSlot()).thenReturn(clicked == top ? DuelProposalEditor.ARENA : 0);
                when(event.getCurrentItem()).thenReturn(clicked == top ? null : sword);
                listener.onClick(event);
                verify(event).setCancelled(true);
            }
        }
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getView()).thenReturn(view);
        listener.onDrag(drag);
        verify(drag).setCancelled(true);

        assertEquals(1, editor.draft().items(DuelSide.CHALLENGER).size(), "the bottom click staked a reference");
        assertSame(sword, h.alice.contents[0]);
        verify(h.alice.player().getInventory(), never()).setItem(anyInt(), any());
    }

    // ==================== Chat offers ====================

    @Test
    void aSentOfferReachesTheReceiverWithIdStampedButtons() {
        openEditor();
        u.clickTop(h.alice, DuelProposalEditor.LEFT_COINS[3]);
        u.clickTop(h.alice, DuelProposalEditor.SEND);

        assertNull(u.screen(h.alice), "the editor closed");
        DuelOffer offer = currentOfferOf(h.alice);
        long id = offer.offerId();
        assertEquals(List.of("/duel view " + id, "/duel accept " + id, "/duel counter " + id, "/duel decline " + id),
                DuelUiHarness.buttons(h.bob));
        String chat = DuelUiHarness.allChat(h.bob);
        assertTrue(chat.contains("Alice challenges you to a duel at Arena (offer 1/3, 60 s)"), chat);
        assertTrue(chat.contains("Alice stakes: 100 coins"), chat);
        assertTrue(chat.contains("You stake: nothing"), chat);
        assertTrue(DuelUiHarness.allChat(h.alice).contains("Duel offer 1/3 sent to Bob"));
    }

    @Test
    void dropModeIsSpelledOutInCapitals() {
        h.arena = DuelHarness.arena(true, true, false, false);
        DuelProposalEditor.Holder editor = openEditor();
        u.clickTop(h.alice, DuelProposalEditor.GEAR);
        assertEquals(GearMode.OWN_GEAR_DROP, editor.draft().gearMode());
        u.clickTop(h.alice, DuelProposalEditor.SEND);

        assertTrue(DuelUiHarness.allChat(h.bob).contains("OWN GEAR, THE LOSER'S INVENTORY DROPS TO THE WINNER"));
    }

    @Test
    void theLastAllowedOfferHasNoCounterAndAStaleButtonCantAcceptAnOldOffer() {
        openEditor();
        u.clickTop(h.alice, DuelProposalEditor.SEND);
        long first = currentOfferOf(h.alice).offerId();

        u.ui.counter(h.bob.player(), first);
        u.clickTop(h.bob, DuelProposalEditor.RIGHT_COINS[2]);
        u.clickTop(h.bob, DuelProposalEditor.SEND);
        long second = currentOfferOf(h.alice).offerId();
        assertTrue(DuelUiHarness.buttons(h.alice).contains("/duel counter " + second));

        u.ui.counter(h.alice.player(), second);
        u.clickTop(h.alice, DuelProposalEditor.SEND);
        DuelOffer last = currentOfferOf(h.alice);
        assertEquals(3, last.number());
        assertTrue(DuelUiHarness.buttons(h.bob).contains("/duel accept " + last.offerId()));
        assertFalse(DuelUiHarness.buttons(h.bob).contains("/duel counter " + last.offerId()), "no Counter on offer 3/3");
        assertTrue(DuelUiHarness.allChat(h.bob).contains("(last offer, 60 s)"));

        u.ui.counter(h.bob.player(), last.offerId());
        assertTrue(DuelUiHarness.chat(h.bob).contains("This is the last offer: accept or decline it."));

        u.ui.view(h.bob.player(), last.offerId());
        assertInstanceOf(DuelOfferView.Holder.class, u.screen(h.bob));
        assertNull(u.iconAt(h.bob, DuelOfferView.COUNTER), "the view has no Counter either");
        assertNotNull(u.iconAt(h.bob, DuelOfferView.ACCEPT));

        u.ui.accept(h.bob.player(), first);
        assertTrue(DuelUiHarness.chat(h.bob).contains("That offer is no longer current."));
        assertTrue(h.service.matches().isEmpty());
    }

    @Test
    void theExpiryWarningComesOnceTenSecondsBeforeTheTurnEnds() {
        openEditor();
        u.clickTop(h.alice, DuelProposalEditor.SEND);
        long id = currentOfferOf(h.alice).offerId();

        for (int s = 0; s < 49; s++) {
            h.seconds(1);
            u.ui.tick();
        }
        assertFalse(DuelUiHarness.allChat(h.bob).contains("expires in"));
        h.seconds(1);
        u.ui.tick();
        h.seconds(1);
        u.ui.tick();

        List<String> warnings = DuelUiHarness.chat(h.bob).stream().filter(l -> l.contains("expires in")).toList();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("Alice's duel offer expires in 10 s."));
        assertTrue(DuelUiHarness.buttons(h.bob).stream().filter(b -> b.equals("/duel accept " + id)).count() >= 2);
    }

    @Test
    void theOfferViewAcceptsLikeTheChatButton() {
        u.ui.invite(h.alice.player(), h.bob.player(), "Bob", true);
        u.ui.view(h.bob.player(), null);
        DuelOfferView.Holder view = assertInstanceOf(DuelOfferView.Holder.class, u.screen(h.bob));

        u.ui.offerView().click(h.bob.player(), view, DuelOfferView.ACCEPT);
        h.runNextTicks();

        assertEquals(1, h.service.matches().size(), "the quick duel locked in");
        assertEquals(DuelPhase.WARMUP, h.service.matches().getFirst().phase());
    }

    @Test
    void theInviteRulesTheUiOwns() {
        u.ui.invite(h.alice.player(), h.alice.player(), "Alice", false);
        assertTrue(DuelUiHarness.chat(h.alice).contains("You can't duel yourself."));

        u.ui.toggle(h.bob.player());
        u.ui.invite(h.alice.player(), h.bob.player(), "Bob", false);
        assertTrue(DuelUiHarness.chat(h.alice).contains("That player doesn't accept duel invites."));
        assertNull(u.screen(h.alice));
        assertTrue(new DuelInviteToggles(dir.resolve("duel-toggles.yml").toFile(), Logger.getLogger("t")).isDisabled(h.bob.id()),
                "the toggle is persisted");

        u.ui.toggle(h.bob.player());
        u.ui.invite(h.alice.player(), null, "Nobody", false);
        assertTrue(DuelUiHarness.chat(h.alice).contains("No player named Nobody is online."));
    }

    // ==================== Lock-in (DESIGN §6.5) ====================

    @Test
    void anItemMovedAwayBeforeLockInIsRefusedAndNothingIsTaken() {
        ItemStack sword = h.items.item("sword");
        ItemStack bobsShield = h.items.item("shield");
        h.alice.give(sword);
        h.bob.give(bobsShield);
        openEditor();
        u.clickBottom(h.alice, 0);
        u.clickTop(h.alice, DuelProposalEditor.SEND);
        long id = currentOfferOf(h.alice).offerId();

        h.alice.contents[0] = null; // Alice drops the sword before Bob accepts
        u.ui.accept(h.bob.player(), id);
        h.runNextTicks();

        assertTrue(h.service.matches().isEmpty());
        assertTrue(h.spool.entries().isEmpty(), "no escrow file");
        assertSame(bobsShield, h.bob.contents[0], "Bob keeps everything");
        assertTrue(h.service.offers().negotiationsOf(h.alice.id()).isEmpty(), "the negotiation closed");
        List<String> told = DuelUiHarness.chat(h.bob).stream().filter(l -> l.startsWith("Duel not started")).toList();
        assertEquals(List.of("Duel not started: Alice no longer has every staked item."), told, "told once");
    }

    @Test
    void anItemMovedToAnotherSlotIsStillTakenAtLockIn() {
        ItemStack sword = h.items.item("sword");
        h.alice.give(sword);
        openEditor();
        u.clickBottom(h.alice, 0);
        u.clickTop(h.alice, DuelProposalEditor.SEND);
        long id = currentOfferOf(h.alice).offerId();

        h.alice.contents[0] = null;
        h.alice.contents[5] = sword;
        u.ui.accept(h.bob.player(), id);
        h.runNextTicks();

        assertEquals(1, h.service.matches().size());
        assertNull(h.alice.contents[5], "taken from where it is now");
    }

    @Test
    void anApiRefusalHandsTheItemsBack() {
        ItemStack sword = h.items.item("sword");
        ItemStack axe = h.items.item("axe");
        h.alice.give(sword);
        h.bob.give(axe);
        doReturn(CompletableFuture.failedFuture(new DuelRefusedException("url", 422, "PairCooldown",
                "You two dueled too recently.", "{}"))).when(h.api).lock(any());
        openEditor();
        u.clickBottom(h.alice, 0);
        u.clickTop(h.alice, DuelProposalEditor.SEND);
        u.ui.counter(h.bob.player(), currentOfferOf(h.alice).offerId());
        u.clickBottom(h.bob, 0); // Bob adds his own axe
        u.clickTop(h.bob, DuelProposalEditor.SEND);
        long counterId = currentOfferOf(h.alice).offerId();

        u.ui.accept(h.alice.player(), counterId);
        h.runNextTicks();

        assertTrue(h.service.matches().isEmpty());
        assertTrue(h.alice.received().contains(sword), "Alice has her sword back");
        assertTrue(h.bob.received().contains(axe), "Bob has his axe back");
        assertTrue(h.spool.entries().stream().noneMatch(e -> e.owesAnything()), "nothing left in escrow");
        List<String> told = DuelUiHarness.chat(h.alice).stream().filter(l -> l.contains("dueled too recently")).toList();
        assertEquals(1, told.size(), told.toString());
    }

    // ==================== KIT_CHOICE in the warmup ====================

    @Test
    void theKitChoicePromptAndPickerShowTheOpponentsPick() {
        h.arena = DuelHarness.arena(true, false, false, true);
        DuelProposalEditor.Holder editor = openEditor();
        u.clickTop(h.alice, DuelProposalEditor.GEAR);
        assertEquals(GearMode.KIT_CHOICE, editor.draft().gearMode());
        u.clickTop(h.alice, DuelProposalEditor.SEND);
        u.ui.accept(h.bob.player(), currentOfferOf(h.alice).offerId());
        h.runNextTicks();
        assertEquals(DuelPhase.WARMUP, h.service.matches().getFirst().phase());

        List<String> prompt = DuelUiHarness.buttons(h.bob);
        assertTrue(prompt.contains("/duel kit " + DuelHarness.KIT_A) && prompt.contains("/duel kit " + DuelHarness.KIT_B), prompt.toString());

        u.ui.kit(h.alice.player(), "archer");
        assertTrue(DuelUiHarness.chat(h.alice).contains("You will fight with Archer."));
        assertTrue(DuelUiHarness.chat(h.bob).contains("Alice picked Archer."));

        u.ui.kit(h.bob.player(), null);
        assertInstanceOf(DuelKitPicker.Holder.class, u.screen(h.bob));
        assertTrue(u.iconAt(h.bob, DuelKitPicker.INFO).contains("Alice picked Archer."));
        assertTrue(u.iconAt(h.bob, DuelKitPicker.FIRST_KIT).contains("Picked"), "Bob's placeholder is the first kit");

        h.seconds(1); // warmup over → countdown
        u.ui.kit(h.bob.player(), "Archer");
        assertTrue(DuelUiHarness.chat(h.bob).contains("The kits are locked now."));
    }

    @Test
    void outsideAnArenaNobodyCanBeChallenged() {
        u.inArena = false;
        u.ui.invite(h.alice.player(), h.bob.player(), "Bob", false);

        assertNull(u.screen(h.alice));
        assertTrue(DuelUiHarness.chat(h.alice).contains("Stand inside an arena to challenge a player."));
    }
}
