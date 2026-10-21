package net.knightsandkings.knk.core.duel;

import net.knightsandkings.knk.core.duel.DuelOfferBook.CloseReason;
import net.knightsandkings.knk.core.duel.DuelOfferBook.Outcome;
import net.knightsandkings.knk.core.duel.DuelOfferBook.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static net.knightsandkings.knk.core.duel.DuelTestSupport.coins;
import static net.knightsandkings.knk.core.duel.DuelTestSupport.friendly;
import static net.knightsandkings.knk.core.duel.DuelTestSupport.item;
import static net.knightsandkings.knk.core.duel.DuelTestSupport.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuelOfferBookTest {
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();
    private final UUID dave = UUID.randomUUID();
    private final UUID erin = UUID.randomUUID();

    private final DuelOfferBook book = new DuelOfferBook(3, 10, s -> s.material().equals("WRITTEN_BOOK"),
            s -> switch (s.material()) {
                case "DIAMOND_SWORD" -> 1_000L * s.amount();
                case "BOW" -> 400L * s.amount();
                default -> 0L;
            });

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void maxOffersBoundsTheTurns(int maxOffers) {
        DuelArenaSettings settings = settings(maxOffers);
        Outcome sent = book.invite(alice, bob, settings, friendly(), 0);
        assertTrue(sent.ok());
        DuelOffer offer = sent.offer();
        assertEquals(1, offer.number());
        UUID[] players = {alice, bob};
        for (int n = 2; n <= maxOffers; n++) {
            UUID author = players[(n - 1) % 2];
            Outcome countered = book.counter(author, offer.offerId(), friendly(), n);
            assertTrue(countered.ok(), "offer " + n + ": " + countered.status());
            assertNotEquals(offer.offerId(), countered.offer().offerId(), "every offer gets a fresh id");
            offer = countered.offer();
            assertEquals(n, offer.number());
        }
        assertTrue(offer.last());
        assertFalse(offer.canBeCountered());
        UUID receiver = players[maxOffers % 2];
        assertEquals(Status.NO_COUNTERS_LEFT, book.counter(receiver, offer.offerId(), friendly(), 10).status());
        Outcome accepted = book.accept(receiver, offer.offerId(), 10);
        assertTrue(accepted.ok());
        assertEquals(DuelNegotiation.State.LOCKING, accepted.negotiation().state());
    }

    @Test
    void offersAlternateAndOnlyTheReceiverAnswers() {
        DuelOffer first = book.invite(alice, bob, settings(3), friendly(), 0).offer();
        assertEquals(DuelSide.CHALLENGER, first.author());
        assertEquals(Status.NOT_YOUR_TURN, book.accept(alice, first.offerId(), 1).status());
        assertEquals(Status.NOT_YOUR_TURN, book.counter(alice, first.offerId(), friendly(), 1).status());
        DuelOffer second = book.counter(bob, first.offerId(), coins(100, 100), 1).offer();
        assertEquals(DuelSide.OPPONENT, second.author());
        assertEquals(alice, book.negotiation(1).awaitedPlayer());
    }

    @Test
    void aStaleOfferIdIsRefused() {
        DuelOffer first = book.invite(alice, bob, settings(3), coins(100, 100), 0).offer();
        DuelOffer second = book.counter(bob, first.offerId(), coins(200, 200), 1).offer();
        // Bob's counter replaced offer #1: an old chat button for #1 can't accept anything.
        assertEquals(Status.STALE_OFFER, book.accept(bob, first.offerId(), 2).status());
        assertEquals(Status.STALE_OFFER, book.decline(bob, first.offerId(), 2).status());
        assertEquals(Status.UNKNOWN_NEGOTIATION, book.accept(bob, 9_999, 2).status());
        assertNull(book.byCurrentOfferId(first.offerId()));
        assertTrue(book.accept(alice, second.offerId(), 2).ok());
    }

    @Test
    void anOfferExpiresAfterOfferSeconds() {
        DuelOffer offer = book.invite(alice, bob, settings(3), friendly(), 0).offer();
        assertEquals(DuelTicks.ofSeconds(60), offer.expiresAtTick());
        assertTrue(book.tick(DuelTicks.ofSeconds(60) - 1).isEmpty());
        List<DuelOfferBook.Closed> expired = book.tick(DuelTicks.ofSeconds(60));
        assertEquals(1, expired.size());
        assertEquals(CloseReason.EXPIRED, expired.getFirst().reason());
        assertFalse(book.isNegotiating(alice));
    }

    @Test
    void acceptingAnExpiredOfferClosesIt() {
        DuelOffer offer = book.invite(alice, bob, settings(3), friendly(), 0).offer();
        Outcome late = book.accept(bob, offer.offerId(), DuelTicks.ofSeconds(60));
        assertEquals(Status.OFFER_EXPIRED, late.status());
        assertFalse(book.isNegotiating(bob));
    }

    @Test
    void aCounterRestartsTheTurnTimer() {
        DuelOffer first = book.invite(alice, bob, settings(3), friendly(), 0).offer();
        DuelOffer second = book.counter(bob, first.offerId(), friendly(), DuelTicks.ofSeconds(50)).offer();
        assertEquals(DuelTicks.ofSeconds(110), second.expiresAtTick());
        assertTrue(book.tick(DuelTicks.ofSeconds(100)).isEmpty());
    }

    @Test
    void inviteLimits() {
        assertEquals(Status.SELF_INVITE, book.invite(alice, alice, settings(3), friendly(), 0).status());
        assertTrue(book.invite(alice, bob, settings(3), friendly(), 0).ok());
        assertEquals(Status.ALREADY_INVITING, book.invite(alice, carol, settings(3), friendly(), 1000).status());
        assertEquals(Status.PAIR_ALREADY_NEGOTIATING, book.invite(bob, alice, settings(3), friendly(), 0).status());
        assertTrue(book.invite(carol, bob, settings(3), friendly(), 0).ok());
        assertTrue(book.invite(dave, bob, settings(3), friendly(), 0).ok());
        assertEquals(Status.TOO_MANY_INCOMING, book.invite(erin, bob, settings(3), friendly(), 0).status());
        assertEquals(3, book.incomingOf(bob).size());
    }

    @Test
    void sendCooldown() {
        DuelOffer offer = book.invite(alice, bob, settings(3), friendly(), 0).offer();
        book.decline(bob, offer.offerId(), 20);
        Outcome again = book.invite(alice, carol, settings(3), friendly(), 100);
        assertEquals(Status.ON_COOLDOWN, again.status());
        assertEquals(5, again.cooldownSeconds());
        assertTrue(book.invite(alice, carol, settings(3), friendly(), DuelTicks.ofSeconds(10)).ok());
    }

    @Test
    void aRefusedInviteStartsNoCooldown() {
        book.setInvitesDisabled(bob, true);
        assertEquals(Status.INVITES_DISABLED, book.invite(alice, bob, settings(3), friendly(), 0).status());
        assertTrue(book.invite(alice, carol, settings(3), friendly(), 1).ok());
    }

    @Test
    void toggleRefusesInvitesAndSurvivesForget() {
        book.setInvitesDisabled(bob, true);
        book.forget(bob);
        assertTrue(book.invitesDisabled(bob));
        assertEquals(Status.INVITES_DISABLED, book.invite(alice, bob, settings(3), friendly(), 0).status());
        book.setInvitesDisabled(bob, false);
        assertTrue(book.invite(alice, bob, settings(3), friendly(), 0).ok());
    }

    @Test
    void forgetOnQuitClosesEveryOpenNegotiation() {
        book.invite(alice, bob, settings(3), friendly(), 0);
        book.invite(bob, carol, settings(3), friendly(), 0);
        book.invite(dave, erin, settings(3), friendly(), 0);
        List<DuelOfferBook.Closed> closed = book.forget(bob);
        assertEquals(2, closed.size());
        assertTrue(closed.stream().allMatch(c -> c.reason() == CloseReason.PLAYER_GONE && bob.equals(c.closedBy())));
        assertFalse(book.isNegotiating(bob));
        assertTrue(book.isNegotiating(dave));
    }

    @Test
    void forgetLeavesALockingNegotiationToTheLockIn() {
        DuelOffer offer = book.invite(alice, bob, settings(3), friendly(), 0).offer();
        book.accept(bob, offer.offerId(), 1);
        assertTrue(book.forget(bob).isEmpty());
        assertTrue(book.isLocking(bob));
    }

    @Test
    void lockSucceededSupersedesTheirOtherNegotiations() {
        DuelOffer offer = book.invite(alice, bob, settings(3), friendly(), 0).offer();
        book.invite(carol, bob, settings(3), friendly(), 0);
        book.invite(dave, erin, settings(3), friendly(), 0);
        long id = book.accept(bob, offer.offerId(), 1).negotiation().negotiationId();
        assertEquals(Status.PLAYER_BUSY, book.invite(erin, alice, settings(3), friendly(), 2).status());
        List<DuelOfferBook.Closed> superseded = book.lockSucceeded(id);
        assertEquals(1, superseded.size());
        assertEquals(carol, superseded.getFirst().negotiation().inviter());
        assertEquals(CloseReason.SUPERSEDED, superseded.getFirst().reason());
        assertFalse(book.isNegotiating(bob));
        assertTrue(book.isNegotiating(dave));
        assertTrue(book.lockSucceeded(id).isEmpty(), "once");
    }

    @Test
    void aPlayerLockingOneOfferCantAcceptAnother() {
        DuelOffer fromAlice = book.invite(alice, bob, settings(3), friendly(), 0).offer();
        DuelOffer fromCarol = book.invite(carol, bob, settings(3), friendly(), 0).offer();
        assertTrue(book.accept(bob, fromAlice.offerId(), 1).ok());
        assertEquals(Status.PLAYER_BUSY, book.accept(bob, fromCarol.offerId(), 1).status());
    }

    @Test
    void lockFailedClosesTheNegotiation() {
        DuelOffer offer = book.invite(alice, bob, settings(3), friendly(), 0).offer();
        long id = book.accept(bob, offer.offerId(), 1).negotiation().negotiationId();
        DuelOfferBook.Closed closed = book.lockFailed(id);
        assertEquals(CloseReason.LOCK_REFUSED, closed.reason());
        assertFalse(book.isNegotiating(alice));
        assertNull(book.lockFailed(id));
    }

    @Test
    void cancelByEitherPlayer() {
        long id = book.invite(alice, bob, settings(3), friendly(), 0).negotiation().negotiationId();
        assertEquals(Status.UNKNOWN_NEGOTIATION, book.cancel(carol, id).status());
        assertTrue(book.cancel(bob, id).ok());
        assertFalse(book.isNegotiating(alice));
    }

    @Test
    void lockingNegotiationsDontExpire() {
        DuelOffer offer = book.invite(alice, bob, settings(3), friendly(), 0).offer();
        book.accept(bob, offer.offerId(), 1);
        assertTrue(book.tick(DuelTicks.ofSeconds(600)).isEmpty());
    }

    @Test
    void onlyTheOwnerAddsItems() {
        StakeContract claimsBobsHelmet = new StakeContract(GearMode.OWN_GEAR_KEEP, null,
                Stake.NONE, new Stake(0, List.of(item("bob-helmet", "DIAMOND_HELMET"))));
        Outcome refused = book.invite(alice, bob, settings(3), claimsBobsHelmet, 0);
        assertEquals(Status.INVALID_CONTRACT, refused.status());
        assertEquals(StakeViolation.Code.ITEM_ADDED_BY_OTHER_SIDE, refused.violations().getFirst().code());
        assertFalse(book.isNegotiating(alice));

        StakeContract aliceSword = new StakeContract(GearMode.OWN_GEAR_KEEP, null,
                new Stake(0, List.of(item("alice-sword", "DIAMOND_SWORD"))), Stake.coins(500));
        DuelOffer first = book.invite(alice, bob, settings(3), aliceSword, 0).offer();
        // Bob may remove Alice's sword and add his own items...
        StakeContract bobCounter = new StakeContract(GearMode.OWN_GEAR_KEEP, null,
                Stake.coins(500), new Stake(0, List.of(item("bob-bow", "BOW"))));
        DuelOffer second = book.counter(bob, first.offerId(), bobCounter, 1).offer();
        // ...but Alice can't put Bob's bow back in at a different amount, nor add another of his items.
        StakeContract aliceEdits = new StakeContract(GearMode.OWN_GEAR_KEEP, null,
                Stake.coins(500), new Stake(0, List.of(new StakeItem("bob-bow", "BOW", 2))));
        assertEquals(Status.INVALID_CONTRACT, book.counter(alice, second.offerId(), aliceEdits, 2).status());
        StakeContract aliceRemoves = new StakeContract(GearMode.OWN_GEAR_KEEP, null, Stake.coins(500), Stake.coins(250));
        assertTrue(book.counter(alice, second.offerId(), aliceRemoves, 2).ok());
    }

    @Test
    void theStakeRatioUsesTheItemValues() {
        StakeContract dirtFor500 = new StakeContract(GearMode.OWN_GEAR_KEEP, null,
                new Stake(0, List.of(item("d", "DIRT"))), Stake.coins(500));
        Outcome refused = book.invite(alice, bob, settings(3), dirtFor500, 0);
        assertEquals(Status.INVALID_CONTRACT, refused.status());
        assertEquals(StakeViolation.Code.STAKE_RATIO, refused.violations().getFirst().code());
        StakeContract swordFor500 = new StakeContract(GearMode.OWN_GEAR_KEEP, null,
                new Stake(0, List.of(item("s", "DIAMOND_SWORD"))), Stake.coins(500));
        assertTrue(book.invite(alice, bob, settings(3), swordFor500, 0).ok());
    }

    @Test
    void theBlocklistAppliesToOffers() {
        StakeContract book1 = new StakeContract(GearMode.OWN_GEAR_KEEP, null,
                new Stake(0, List.of(item("b", "WRITTEN_BOOK"))), Stake.NONE);
        Outcome refused = book.invite(alice, bob, settings(3), book1, 0);
        assertEquals(StakeViolation.Code.ITEM_BLOCKED, refused.violations().getFirst().code());
    }
}
