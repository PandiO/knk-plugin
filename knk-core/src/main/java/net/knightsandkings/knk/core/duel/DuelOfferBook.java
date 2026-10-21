package net.knightsandkings.knk.core.duel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * All open negotiations on the server (DESIGN §6.1, §6.3): invitations, offers and counters, turn
 * order with {@code MaxOffers} (D2), per-turn expiry, the incoming/outgoing limits, the send cooldown,
 * {@code /duel toggle}, and forgetting a player who quits, dies or joins a siege.
 *
 * <p>Nothing is held while players negotiate, so closing a negotiation never needs a refund. The
 * runtime checks the rules that need the world (inside an arena, combat tag, minigames, freeze, the
 * ignore list, {@code TeleportService.check}, the queue cap) before calling in; this class keeps the
 * protocol straight. Time is the server tick ({@link DuelTicks}). Not thread-safe: main thread only.
 */
public final class DuelOfferBook {

    /** DESIGN §12 {@code duels.invite.max-incoming}. */
    public static final int DEFAULT_MAX_INCOMING = 3;
    /** DESIGN §12 {@code duels.invite.send-cooldown-seconds}. */
    public static final int DEFAULT_SEND_COOLDOWN_SECONDS = 10;

    public enum Status {
        OK,
        SELF_INVITE,
        /** The invitee turned invites off ({@code /duel toggle}). */
        INVITES_DISABLED,
        /** The inviter already has an outgoing negotiation. */
        ALREADY_INVITING,
        /** These two players already negotiate (either direction). */
        PAIR_ALREADY_NEGOTIATING,
        /** The invitee has {@code max-incoming} negotiations. */
        TOO_MANY_INCOMING,
        /** {@link Outcome#cooldownSeconds()} tells how long. */
        ON_COOLDOWN,
        /** One of the players is locking in another accepted offer. */
        PLAYER_BUSY,
        /** No open negotiation with that id, or the player isn't in it. */
        UNKNOWN_NEGOTIATION,
        /** The offer id isn't the negotiation's current offer (a stale chat button, V5). */
        STALE_OFFER,
        /** The player isn't the one who answers the current offer. */
        NOT_YOUR_TURN,
        /** The turn ran out; the negotiation is now closed. */
        OFFER_EXPIRED,
        /** The current offer is the last allowed one: accept or decline only. */
        NO_COUNTERS_LEFT,
        /** {@link Outcome#violations()} names the reasons. */
        INVALID_CONTRACT,
        /** The negotiation isn't waiting for an answer (it is locking). */
        NOT_AWAITING_RESPONSE;

        public boolean ok() {
            return this == OK;
        }
    }

    public enum CloseReason {
        DECLINED,
        CANCELLED,
        EXPIRED,
        /** A player quit, died, or joined a siege ({@link #forget}). */
        PLAYER_GONE,
        /** Lock-in succeeded: the match took over. */
        LOCKED,
        /** Lock-in was refused (items, coins, eligibility, limits, the API). */
        LOCK_REFUSED,
        /** One of its players locked into another match. */
        SUPERSEDED
    }

    /**
     * The answer to a call. On success {@code offer} is the new (invite, counter) or accepted offer.
     *
     * @param cooldownSeconds for {@link Status#ON_COOLDOWN}, seconds until the next invite; else 0
     */
    public record Outcome(Status status, DuelNegotiation negotiation, DuelOffer offer,
                          List<StakeViolation> violations, int cooldownSeconds) {
        public Outcome {
            violations = List.copyOf(violations);
        }

        static Outcome of(Status status) {
            return new Outcome(status, null, null, List.of(), 0);
        }

        static Outcome of(Status status, DuelNegotiation negotiation) {
            return new Outcome(status, negotiation, negotiation == null ? null : negotiation.currentOffer(), List.of(), 0);
        }

        public boolean ok() {
            return status.ok();
        }
    }

    /** A negotiation that closed, so the runtime can tell both players. */
    public record Closed(DuelNegotiation negotiation, CloseReason reason, UUID closedBy) {}

    private final int maxIncoming;
    private final long sendCooldownTicks;
    private final Predicate<StakeItem> blocked;
    private final ToLongFunction<StakeItem> itemValue;

    private final Map<Long, DuelNegotiation> open = new LinkedHashMap<>();
    private final Map<UUID, Long> cooldownUntilTick = new HashMap<>();
    private final Set<UUID> invitesDisabled = new HashSet<>();
    private long nextNegotiationId = 1;
    private long nextOfferId = 1;

    /** Without item values: only coins count toward the stake ratio. */
    public DuelOfferBook(int maxIncoming, int sendCooldownSeconds, Predicate<StakeItem> blocked) {
        this(maxIncoming, sendCooldownSeconds, blocked, StakeContractValidator.NO_ITEM_VALUE);
    }

    /**
     * @param blocked   the stake blocklist predicate (DESIGN §7.6), applied to every offer; null = none
     * @param itemValue the coin value of a staked stack for the stake ratio (the blueprint's
     *                  {@code BasePriceMin} × amount; 0 for an item without one); null = none
     */
    public DuelOfferBook(int maxIncoming, int sendCooldownSeconds, Predicate<StakeItem> blocked,
                         ToLongFunction<StakeItem> itemValue) {
        if (maxIncoming < 1) throw new IllegalArgumentException("maxIncoming must be >= 1");
        if (sendCooldownSeconds < 0) throw new IllegalArgumentException("sendCooldownSeconds must be >= 0");
        this.maxIncoming = maxIncoming;
        this.sendCooldownTicks = DuelTicks.ofSeconds(sendCooldownSeconds);
        this.blocked = blocked;
        this.itemValue = itemValue == null ? StakeContractValidator.NO_ITEM_VALUE : itemValue;
    }

    // ==================== Toggle ====================

    /** {@code /duel toggle}. The runtime persists the flag per player and sets it again on join. */
    public void setInvitesDisabled(UUID player, boolean disabled) {
        if (disabled) invitesDisabled.add(player);
        else invitesDisabled.remove(player);
    }

    public boolean invitesDisabled(UUID player) {
        return invitesDisabled.contains(player);
    }

    // ==================== Protocol ====================

    /**
     * Sends offer #1. The send cooldown starts only when the invite goes out.
     *
     * @param contract the inviter's proposal; offer #1 can't stake the invitee's items
     */
    public Outcome invite(UUID inviter, UUID invitee, DuelArenaSettings settings, StakeContract contract, long nowTick) {
        Objects.requireNonNull(inviter, "inviter");
        Objects.requireNonNull(invitee, "invitee");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(contract, "contract");
        if (inviter.equals(invitee)) return Outcome.of(Status.SELF_INVITE);
        if (isLocking(inviter) || isLocking(invitee)) return Outcome.of(Status.PLAYER_BUSY);
        Long until = cooldownUntilTick.get(inviter);
        if (until != null && nowTick < until) {
            return new Outcome(Status.ON_COOLDOWN, null, null, List.of(), DuelTicks.secondsUntil(until, nowTick));
        }
        if (invitesDisabled.contains(invitee)) return Outcome.of(Status.INVITES_DISABLED);
        for (DuelNegotiation n : open.values()) {
            if (n.involves(inviter) && n.involves(invitee)) return Outcome.of(Status.PAIR_ALREADY_NEGOTIATING, n);
        }
        if (outgoingOf(inviter) != null) return Outcome.of(Status.ALREADY_INVITING);
        if (incomingOf(invitee).size() >= maxIncoming) return Outcome.of(Status.TOO_MANY_INCOMING);

        List<StakeViolation> violations =
                StakeContractValidator.validateOffer(null, contract, DuelSide.CHALLENGER, settings, blocked, itemValue);
        if (!violations.isEmpty()) return new Outcome(Status.INVALID_CONTRACT, null, null, violations, 0);

        DuelNegotiation negotiation = new DuelNegotiation(nextNegotiationId++, inviter, invitee, settings);
        DuelOffer offer = newOffer(negotiation, DuelSide.CHALLENGER, contract, nowTick);
        open.put(negotiation.negotiationId(), negotiation);
        cooldownUntilTick.put(inviter, nowTick + sendCooldownTicks);
        return new Outcome(Status.OK, negotiation, offer, List.of(), 0);
    }

    /** {@code /duel counter <id>}: the receiver of the current offer replaces it with their own. */
    public Outcome counter(UUID author, long offerId, StakeContract contract, long nowTick) {
        Objects.requireNonNull(contract, "contract");
        Outcome turn = checkTurn(author, offerId, nowTick);
        if (!turn.ok()) return turn;
        DuelNegotiation negotiation = turn.negotiation();
        DuelOffer current = negotiation.currentOffer();
        if (!current.canBeCountered()) return Outcome.of(Status.NO_COUNTERS_LEFT, negotiation);
        DuelSide side = negotiation.sideOf(author);
        List<StakeViolation> violations = StakeContractValidator.validateOffer(
                current.contract(), contract, side, negotiation.settings(), blocked, itemValue);
        if (!violations.isEmpty()) return new Outcome(Status.INVALID_CONTRACT, negotiation, current, violations, 0);
        DuelOffer offer = newOffer(negotiation, side, contract, nowTick);
        return new Outcome(Status.OK, negotiation, offer, List.of(), 0);
    }

    /**
     * {@code /duel accept <id>}: only the current offer, by its receiver, before it expires. The
     * negotiation moves to {@link DuelNegotiation.State#LOCKING}; the runtime then runs lock-in and
     * reports back with {@link #lockSucceeded} or {@link #lockFailed}.
     */
    public Outcome accept(UUID player, long offerId, long nowTick) {
        Outcome turn = checkTurn(player, offerId, nowTick);
        if (!turn.ok()) return turn;
        DuelNegotiation negotiation = turn.negotiation();
        for (DuelNegotiation other : open.values()) {
            if (other != negotiation && other.state() == DuelNegotiation.State.LOCKING
                    && (other.involves(negotiation.inviter()) || other.involves(negotiation.invitee()))) {
                return Outcome.of(Status.PLAYER_BUSY, negotiation);
            }
        }
        negotiation.setState(DuelNegotiation.State.LOCKING);
        return Outcome.of(Status.OK, negotiation);
    }

    /** {@code /duel decline <id>}: the receiver turns the current offer down; the negotiation closes. */
    public Outcome decline(UUID player, long offerId, long nowTick) {
        Outcome turn = checkTurn(player, offerId, nowTick);
        if (!turn.ok()) return turn;
        close(turn.negotiation());
        return Outcome.of(Status.OK, turn.negotiation());
    }

    /** {@code /duel cancel}: either player ends a negotiation that isn't locking. */
    public Outcome cancel(UUID player, long negotiationId) {
        DuelNegotiation negotiation = open.get(negotiationId);
        if (negotiation == null || !negotiation.involves(player)) return Outcome.of(Status.UNKNOWN_NEGOTIATION);
        if (negotiation.state() != DuelNegotiation.State.AWAITING_RESPONSE) {
            return Outcome.of(Status.NOT_AWAITING_RESPONSE, negotiation);
        }
        close(negotiation);
        return Outcome.of(Status.OK, negotiation);
    }

    /**
     * Lock-in succeeded: the negotiation hands over to the match. Every other negotiation of either
     * player closes ({@link CloseReason#SUPERSEDED}), since they are now in a match.
     *
     * @return the superseded negotiations
     */
    public List<Closed> lockSucceeded(long negotiationId) {
        DuelNegotiation negotiation = open.get(negotiationId);
        if (negotiation == null || negotiation.state() != DuelNegotiation.State.LOCKING) return List.of();
        close(negotiation);
        List<Closed> superseded = new ArrayList<>();
        for (Iterator<DuelNegotiation> it = open.values().iterator(); it.hasNext(); ) {
            DuelNegotiation other = it.next();
            if (other.involves(negotiation.inviter()) || other.involves(negotiation.invitee())) {
                other.setState(DuelNegotiation.State.CLOSED);
                it.remove();
                superseded.add(new Closed(other, CloseReason.SUPERSEDED, null));
            }
        }
        return List.copyOf(superseded);
    }

    /** Lock-in was refused: the negotiation closes and nothing was taken (or it was handed back). */
    public Closed lockFailed(long negotiationId) {
        DuelNegotiation negotiation = open.get(negotiationId);
        if (negotiation == null || negotiation.state() != DuelNegotiation.State.LOCKING) return null;
        close(negotiation);
        return new Closed(negotiation, CloseReason.LOCK_REFUSED, null);
    }

    /**
     * Expires every negotiation whose current offer ran out. Locking negotiations don't expire. Also
     * prunes finished send cooldowns.
     */
    public List<Closed> tick(long nowTick) {
        List<Closed> expired = new ArrayList<>();
        for (Iterator<DuelNegotiation> it = open.values().iterator(); it.hasNext(); ) {
            DuelNegotiation n = it.next();
            if (n.state() == DuelNegotiation.State.AWAITING_RESPONSE && n.currentOffer().isExpired(nowTick)) {
                n.setState(DuelNegotiation.State.CLOSED);
                it.remove();
                expired.add(new Closed(n, CloseReason.EXPIRED, null));
            }
        }
        cooldownUntilTick.values().removeIf(until -> until <= nowTick);
        return List.copyOf(expired);
    }

    /**
     * The player quit, died or joined a siege (DESIGN §6.3): close their negotiations. A negotiation
     * that is already locking is left to the lock-in, which re-checks both players. The toggle stays
     * (the runtime persists it).
     */
    public List<Closed> forget(UUID player) {
        List<Closed> closed = new ArrayList<>();
        for (Iterator<DuelNegotiation> it = open.values().iterator(); it.hasNext(); ) {
            DuelNegotiation n = it.next();
            if (n.involves(player) && n.state() == DuelNegotiation.State.AWAITING_RESPONSE) {
                n.setState(DuelNegotiation.State.CLOSED);
                it.remove();
                closed.add(new Closed(n, CloseReason.PLAYER_GONE, player));
            }
        }
        return List.copyOf(closed);
    }

    // ==================== Queries ====================

    public DuelNegotiation negotiation(long negotiationId) {
        return open.get(negotiationId);
    }

    /** The open negotiation whose current offer has this id, or null (an old id finds nothing). */
    public DuelNegotiation byCurrentOfferId(long offerId) {
        for (DuelNegotiation n : open.values()) {
            DuelOffer current = n.currentOffer();
            if (current != null && current.offerId() == offerId) return n;
        }
        return null;
    }

    /** The open negotiation this player started, or null. */
    public DuelNegotiation outgoingOf(UUID player) {
        for (DuelNegotiation n : open.values()) {
            if (n.inviter().equals(player)) return n;
        }
        return null;
    }

    public List<DuelNegotiation> incomingOf(UUID player) {
        List<DuelNegotiation> out = new ArrayList<>();
        for (DuelNegotiation n : open.values()) {
            if (n.invitee().equals(player)) out.add(n);
        }
        return List.copyOf(out);
    }

    /** Every open negotiation the player is in, oldest first. */
    public List<DuelNegotiation> negotiationsOf(UUID player) {
        List<DuelNegotiation> out = new ArrayList<>();
        for (DuelNegotiation n : open.values()) {
            if (n.involves(player)) out.add(n);
        }
        return List.copyOf(out);
    }

    public boolean isNegotiating(UUID player) {
        return !negotiationsOf(player).isEmpty();
    }

    /** In a negotiation lock (D18: such a player can't accept another offer). */
    public boolean isLocking(UUID player) {
        for (DuelNegotiation n : open.values()) {
            if (n.state() == DuelNegotiation.State.LOCKING && n.involves(player)) return true;
        }
        return false;
    }

    public int cooldownSeconds(UUID player, long nowTick) {
        Long until = cooldownUntilTick.get(player);
        return until == null ? 0 : DuelTicks.secondsUntil(until, nowTick);
    }

    // ==================== Internals ====================

    private Outcome checkTurn(UUID player, long offerId, long nowTick) {
        DuelNegotiation negotiation = null;
        for (DuelNegotiation n : open.values()) {
            if (n.involves(player) && n.offers().stream().anyMatch(o -> o.offerId() == offerId)) {
                negotiation = n;
                break;
            }
        }
        if (negotiation == null) return Outcome.of(Status.UNKNOWN_NEGOTIATION);
        if (negotiation.state() != DuelNegotiation.State.AWAITING_RESPONSE) {
            return Outcome.of(Status.NOT_AWAITING_RESPONSE, negotiation);
        }
        DuelOffer current = negotiation.currentOffer();
        if (current.offerId() != offerId) return Outcome.of(Status.STALE_OFFER, negotiation);
        if (current.isExpired(nowTick)) {
            negotiation.setState(DuelNegotiation.State.CLOSED);
            open.remove(negotiation.negotiationId());
            return Outcome.of(Status.OFFER_EXPIRED, negotiation);
        }
        if (negotiation.sideOf(player) != current.receiver()) return Outcome.of(Status.NOT_YOUR_TURN, negotiation);
        return Outcome.of(Status.OK, negotiation);
    }

    private DuelOffer newOffer(DuelNegotiation negotiation, DuelSide author, StakeContract contract, long nowTick) {
        int number = negotiation.offerCount() + 1;
        DuelArenaSettings settings = negotiation.settings();
        DuelOffer offer = new DuelOffer(nextOfferId++, number, author, contract,
                nowTick + DuelTicks.ofSeconds(settings.offerSeconds()), number >= settings.maxOffers());
        negotiation.addOffer(offer);
        return offer;
    }

    private void close(DuelNegotiation negotiation) {
        negotiation.setState(DuelNegotiation.State.CLOSED);
        open.remove(negotiation.negotiationId());
    }
}
