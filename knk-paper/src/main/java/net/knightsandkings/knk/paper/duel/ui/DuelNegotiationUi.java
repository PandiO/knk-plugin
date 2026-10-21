package net.knightsandkings.knk.paper.duel.ui;

import net.knightsandkings.knk.core.domain.arenas.KnkArena;
import net.knightsandkings.knk.core.domain.arenas.KnkArenaKit;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.CheckResult;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.ItemSummary;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.LockRequest;
import net.knightsandkings.knk.core.duel.ArenaQueue;
import net.knightsandkings.knk.core.duel.DuelArenaSettings;
import net.knightsandkings.knk.core.duel.DuelNegotiation;
import net.knightsandkings.knk.core.duel.DuelOffer;
import net.knightsandkings.knk.core.duel.DuelOfferBook;
import net.knightsandkings.knk.core.duel.DuelPhase;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.DuelStateMachine;
import net.knightsandkings.knk.core.duel.DuelTicks;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.StakeContract;
import net.knightsandkings.knk.core.duel.StakeItem;
import net.knightsandkings.knk.core.duel.StakeViolation;
import net.knightsandkings.knk.core.ports.api.DuelMatchesApi;
import net.knightsandkings.knk.paper.duel.DuelArenas;
import net.knightsandkings.knk.paper.duel.DuelEscrowSpool;
import net.knightsandkings.knk.paper.duel.DuelItems;
import net.knightsandkings.knk.paper.duel.DuelMatch;
import net.knightsandkings.knk.paper.duel.DuelObserver;
import net.knightsandkings.knk.paper.duel.DuelService;
import net.knightsandkings.knk.paper.duel.DuelStakeItems;
import net.knightsandkings.knk.paper.duel.DuelTexts;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The negotiation UI of arena duels (DESIGN §6, plan Phase 5): the invite rules the runtime leaves to the UI (inside
 * an enabled arena, the ignore list, visibility, the advisory queue cap, the teleport pre-check), the proposal
 * editor, the offer view, the clickable chat offers with the 10 s expiry warning, the accept that starts P4's
 * lock-in ({@link DuelService#accept}), and the {@code KIT_CHOICE} picker in the warmup. Main thread only; every API
 * call is async.
 */
public final class DuelNegotiationUi implements DuelObserver, DuelProposalEditor.Actions, DuelOfferView.Actions,
        DuelKitPicker.Actions {

    /** The anvil text input ({@code AnvilCaptureManager.startTextCapture}). */
    @FunctionalInterface
    public interface TextInput {
        void capture(Player player, String prompt, Consumer<String> onComplete, Runnable onCancel);
    }

    /** What the UI needs from the plugin (fakes in tests). */
    public record Ports(
            DuelService service,
            DuelMatchesApi api,
            DuelItems items,
            Function<Player, Optional<KnkArena>> arenaAt,
            Function<Integer, CompletableFuture<Optional<KnkArena>>> freshArena,
            BiPredicate<UUID, UUID> ignores,
            BiPredicate<Player, Player> canSee,
            Function<UUID, Player> players,
            Function<UUID, Integer> userIds,
            Function<UUID, OptionalLong> coinBalance,
            BiFunction<Player, KnkArena, CompletableFuture<Optional<String>>> lobbyCheck,
            TextInput textInput,
            DuelGui gui,
            IntFunction<List<String>> kitPreview,
            DuelInviteToggles toggles,
            Executor mainThread,
            Consumer<Runnable> nextTick,
            Logger logger
    ) {
        public Ports {
            Objects.requireNonNull(service, "service");
            Objects.requireNonNull(items, "items");
            Objects.requireNonNull(arenaAt, "arenaAt");
            freshArena = freshArena == null ? id -> CompletableFuture.completedFuture(Optional.empty()) : freshArena;
            ignores = ignores == null ? (viewer, sender) -> false : ignores;
            canSee = canSee == null ? (viewer, target) -> true : canSee;
            Objects.requireNonNull(players, "players");
            Objects.requireNonNull(userIds, "userIds");
            coinBalance = coinBalance == null ? id -> OptionalLong.empty() : coinBalance;
            Objects.requireNonNull(textInput, "textInput");
            Objects.requireNonNull(gui, "gui");
            Objects.requireNonNull(mainThread, "mainThread");
            nextTick = nextTick == null ? Runnable::run : nextTick;
            Objects.requireNonNull(logger, "logger");
        }
    }

    private final Ports ports;
    private final DuelService service;
    private final DuelProposalEditor editor;
    private final DuelOfferView offerView;
    private final DuelKitPicker kitPicker;
    /** Kit names by id, from every arena the UI has seen (kit ids are global). */
    private final Map<Integer, String> kitNames = new ConcurrentHashMap<>();
    private final Map<Integer, String> arenaNames = new HashMap<>();
    /** The draft each player is editing (kept while an anvil or the kit picker is on top). */
    private final Map<UUID, ProposalDraft> drafts = new HashMap<>();
    private final Map<UUID, DuelProposalEditor.Holder> openEditors = new HashMap<>();
    /** Negotiation id → the offer id that already got its expiry warning. */
    private final Map<Long, Long> warned = new HashMap<>();
    /** Negotiations whose lock-in refusal the runtime already told both players about. */
    private final Set<Long> refusalTold = new HashSet<>();

    public DuelNegotiationUi(Ports ports) {
        this.ports = Objects.requireNonNull(ports, "ports");
        this.service = ports.service();
        DuelStakeItems stakeItems = service.stakeItems();
        this.editor = new DuelProposalEditor(new DuelProposalEditor.Context(ports.gui(), this::kitName,
                ports.items()::stakeRefusal, (owner, stack, slot) -> stakeItems.register(owner, stack, slot, 0),
                item -> service.config().itemBlocklist().contains(item.material()), stakeItems::valueOf,
                ports.coinBalance()), this);
        this.offerView = new DuelOfferView(ports.gui(), this::kitName, this::copyOf, this::nameOf, this);
        this.kitPicker = new DuelKitPicker(ports.gui(), this::kitName, ports.kitPreview(), this);
        for (UUID off : toggles()) service.offers().setInvitesDisabled(off, true);
        service.addObserver(this);
    }

    public DuelProposalEditor editor() {
        return editor;
    }

    public DuelOfferView offerView() {
        return offerView;
    }

    public DuelKitPicker kitPicker() {
        return kitPicker;
    }

    // ==================== /duel <player> [quick] ====================

    /** {@code /duel <player>} (the editor) or {@code /duel <player> quick} (no stakes, the default gear mode). */
    public void invite(Player inviter, Player target, String typedName, boolean quick) {
        Optional<String> refusal = inviteRefusal(inviter, target, typedName);
        if (refusal.isPresent()) {
            tell(inviter, refusal.get());
            return;
        }
        KnkArena standing = ports.arenaAt().apply(inviter).orElseThrow();
        ports.freshArena().apply(standing.id()).exceptionally(error -> Optional.empty())
                .thenAcceptAsync(fresh -> {
                    KnkArena arena = fresh.orElse(standing);
                    remember(arena);
                    if (!arena.duelEnabled()) {
                        tell(inviter, "This arena isn't open for duels right now.");
                        return;
                    }
                    Optional<DuelArenaSettings> settings = DuelArenas.settingsOf(arena);
                    if (settings.isEmpty()) {
                        tell(inviter, "This arena isn't set up for duels yet.");
                        return;
                    }
                    lobbyChecks(inviter, target, arena).thenAcceptAsync(denied -> {
                        if (denied.isPresent()) {
                            tell(inviter, denied.get());
                            return;
                        }
                        Optional<String> again = inviteRefusal(inviter, target, typedName);
                        if (again.isPresent()) {
                            tell(inviter, again.get());
                            return;
                        }
                        if (quick) {
                            sendInvite(inviter, target, settings.get(), arena.name(), StakeContract.quick(settings.get()));
                        } else {
                            openEditor(inviter, ProposalDraft.invite(inviter.getUniqueId(), inviter.getName(),
                                    target.getUniqueId(), target.getName(), settings.get(), arena.name()));
                        }
                    }, ports.mainThread());
                }, ports.mainThread());
    }

    /**
     * The invite rules (DESIGN §6.1) for {@code inviter} challenging {@code target}: empty when it may go ahead. The
     * offer book checks its own protocol rules again when the offer is sent; the runtime the accept rules at accept.
     */
    public Optional<String> inviteRefusal(Player inviter, Player target, String typedName) {
        UUID me = inviter.getUniqueId();
        if (target == null || !target.isOnline() || !ports.canSee().test(inviter, target)) {
            return Optional.of("No player named " + typedName + " is online.");
        }
        UUID them = target.getUniqueId();
        if (me.equals(them)) return Optional.of(DuelTexts.offerStatus(DuelOfferBook.Status.SELF_INVITE));
        Optional<String> mine = service.inviteRefusal(inviter);
        if (mine.isPresent()) return mine;
        Optional<KnkArena> arena = ports.arenaAt().apply(inviter);
        if (arena.isEmpty()) return Optional.of("Stand inside an arena to challenge a player.");
        if (!arena.get().duelEnabled()) return Optional.of("This arena isn't open for duels right now.");
        if (ports.ignores().test(them, me) || service.offers().invitesDisabled(them)) {
            return Optional.of(DuelTexts.offerStatus(DuelOfferBook.Status.INVITES_DISABLED));
        }
        if (service.matchOf(them).isPresent()) return Optional.of(target.getName() + " is in a duel right now.");
        DuelOfferBook book = service.offers();
        long now = service.now();
        if (book.outgoingOf(me) != null) return Optional.of(DuelTexts.offerStatus(DuelOfferBook.Status.ALREADY_INVITING)
                + " Use /duel cancel first.");
        for (DuelNegotiation n : book.negotiationsOf(me)) {
            if (n.involves(them)) return Optional.of(DuelTexts.offerStatus(DuelOfferBook.Status.PAIR_ALREADY_NEGOTIATING));
        }
        int cooldown = book.cooldownSeconds(me, now);
        if (cooldown > 0) return Optional.of("Wait " + cooldown + " s before sending another duel invite.");
        if (book.incomingOf(them).size() >= service.config().maxIncoming()) {
            return Optional.of(DuelTexts.offerStatus(DuelOfferBook.Status.TOO_MANY_INCOMING));
        }
        Optional<ArenaQueue> queue = service.queueOf(arena.get().id());
        if (queue.isPresent() && !queue.get().hasRoom()) {
            return Optional.of("The arena is full (" + queue.get().queuedCount() + "/" + queue.get().maxQueuedMatches()
                    + " waiting), try again shortly.");
        }
        return Optional.empty();
    }

    private CompletableFuture<Optional<String>> lobbyChecks(Player inviter, Player target, KnkArena arena) {
        if (ports.lobbyCheck() == null) return CompletableFuture.completedFuture(Optional.empty());
        CompletableFuture<Optional<String>> mine = ports.lobbyCheck().apply(inviter, arena);
        CompletableFuture<Optional<String>> theirs = ports.lobbyCheck().apply(target, arena);
        return mine.thenCombine(theirs, (a, b) -> a.map(m -> "You can't be teleported to the arena: " + m)
                        .or(() -> b.map(m -> target.getName() + " can't be teleported to the arena: " + m)))
                .exceptionally(error -> Optional.of("Teleporting isn't available right now."));
    }

    // ==================== /duel counter | accept | decline | cancel | view ====================

    /** {@code /duel counter <id>}: the editor pre-filled with that offer. */
    public void counter(Player player, long offerId) {
        DuelNegotiation negotiation = service.offers().byCurrentOfferId(offerId);
        Optional<String> refusal = answerRefusal(player, negotiation, offerId);
        if (refusal.isPresent()) {
            tell(player, refusal.get());
            return;
        }
        DuelOffer offer = negotiation.currentOffer();
        if (!offer.canBeCountered()) {
            tell(player, DuelTexts.offerStatus(DuelOfferBook.Status.NO_COUNTERS_LEFT));
            return;
        }
        UUID other = negotiation.playerOf(offer.author());
        ProposalDraft draft = ProposalDraft.counter(negotiation, player.getName(), nameOf(other),
                arenaName(negotiation.arenaId()), refId -> service.stakeItems().get(refId)
                        .map(ref -> new ProposalDraft.Item(new StakeItem(refId, ref.copy().getType().name(),
                                ref.copy().getAmount()), ref.copy(), ref.slotHint())));
        drafts.put(player.getUniqueId(), draft);
        ports.nextTick().accept(() -> {
            if (drafts.get(player.getUniqueId()) == draft && player.isOnline()) openEditor(player, draft);
        });
    }

    /** {@code /duel accept <id>}: P4's lock-in (DESIGN §6.5); the runtime tells both players if it is refused. */
    public void accept(Player player, long offerId) {
        DuelNegotiation negotiation = service.offers().byCurrentOfferId(offerId);
        Optional<String> refusal = answerRefusal(player, negotiation, offerId);
        if (refusal.isPresent()) {
            tell(player, refusal.get());
            return;
        }
        long negotiationId = negotiation.negotiationId();
        closeMenusOf(negotiation);
        service.accept(player, offerId).whenComplete((outcome, error) -> ports.mainThread().execute(() -> {
            if (error != null) {
                ports.logger().log(Level.WARNING, "[Duel] Accept of offer " + offerId + " failed", error);
                tell(player, "The duel couldn't be started; nothing was taken.");
                return;
            }
            boolean told = refusalTold.remove(negotiationId);
            if (!outcome.ok() && !told && outcome.message() != null) tell(player, outcome.message());
        }));
    }

    /** {@code /duel decline <id>}. */
    public void decline(Player player, long offerId) {
        DuelNegotiation negotiation = negotiationWithOffer(player.getUniqueId(), offerId);
        DuelOfferBook.Outcome outcome = service.offers().decline(player.getUniqueId(), offerId, service.now());
        if (!outcome.ok()) {
            tell(player, DuelTexts.offerStatus(outcome.status()));
            return;
        }
        closed(outcome.negotiation());
        UUID other = otherOf(outcome.negotiation(), player.getUniqueId());
        tell(player, "You declined the duel with " + nameOf(other) + ".");
        tellPlayer(other, player.getName() + " declined the duel.");
        if (negotiation != null) closeMenusOf(negotiation);
    }

    /** {@code /duel cancel [id]}: withdraws the player's own invite, or ends the negotiation of that offer. */
    public void cancel(Player player, Long offerId) {
        UUID me = player.getUniqueId();
        DuelNegotiation target = offerId != null ? negotiationWithOffer(me, offerId) : service.offers().outgoingOf(me);
        if (target == null && offerId == null) {
            List<DuelNegotiation> mine = service.offers().negotiationsOf(me);
            if (mine.size() == 1) target = mine.getFirst();
        }
        if (target == null) {
            tell(player, offerId == null ? "You have no duel offer to cancel." : DuelTexts.offerStatus(DuelOfferBook.Status.UNKNOWN_NEGOTIATION));
            return;
        }
        DuelOfferBook.Outcome outcome = service.offers().cancel(me, target.negotiationId());
        if (!outcome.ok()) {
            tell(player, DuelTexts.offerStatus(outcome.status()));
            return;
        }
        closed(target);
        closeMenusOf(target);
        UUID other = otherOf(target, me);
        tell(player, "Duel with " + nameOf(other) + " cancelled.");
        tellPlayer(other, player.getName() + " withdrew the duel offer.");
    }

    /** {@code /duel view [id]}: the offer view of that offer (or of the player's only negotiation). */
    public void view(Player player, Long offerId) {
        UUID me = player.getUniqueId();
        DuelNegotiation negotiation = null;
        if (offerId != null) {
            negotiation = service.offers().byCurrentOfferId(offerId);
            if (negotiation == null || !negotiation.involves(me)) {
                tell(player, DuelTexts.offerStatus(negotiationWithOffer(me, offerId) != null
                        ? DuelOfferBook.Status.STALE_OFFER : DuelOfferBook.Status.UNKNOWN_NEGOTIATION));
                return;
            }
        } else {
            List<DuelNegotiation> mine = service.offers().negotiationsOf(me);
            if (mine.isEmpty()) {
                tell(player, "You have no open duel offer.");
                return;
            }
            negotiation = mine.getFirst();
        }
        DuelOffer offer = negotiation.currentOffer();
        offerView.open(player, negotiation, arenaName(negotiation.arenaId()),
                Math.max(0, DuelTicks.secondsUntil(offer.expiresAtTick(), service.now())));
    }

    private Optional<String> answerRefusal(Player player, DuelNegotiation current, long offerId) {
        UUID me = player.getUniqueId();
        if (current == null || !current.involves(me)) {
            return Optional.of(DuelTexts.offerStatus(negotiationWithOffer(me, offerId) != null
                    ? DuelOfferBook.Status.STALE_OFFER : DuelOfferBook.Status.UNKNOWN_NEGOTIATION));
        }
        if (current.state() != DuelNegotiation.State.AWAITING_RESPONSE) {
            return Optional.of(DuelTexts.offerStatus(DuelOfferBook.Status.NOT_AWAITING_RESPONSE));
        }
        if (current.currentOffer().isExpired(service.now())) return Optional.of(DuelTexts.offerStatus(DuelOfferBook.Status.OFFER_EXPIRED));
        if (current.sideOf(me) != current.currentOffer().receiver()) {
            return Optional.of(DuelTexts.offerStatus(DuelOfferBook.Status.NOT_YOUR_TURN));
        }
        return Optional.empty();
    }

    // ==================== Sending offers ====================

    private void sendInvite(Player inviter, Player target, DuelArenaSettings settings, String arenaName, StakeContract contract) {
        DuelOfferBook.Outcome outcome = service.offers().invite(inviter.getUniqueId(), target.getUniqueId(),
                bookSettings(settings), contract, service.now());
        if (!outcome.ok()) {
            refused(inviter, outcome, target.getName(), true);
            return;
        }
        announce(outcome.negotiation(), outcome.offer(), arenaName);
    }

    /** Editor: Send. */
    @Override
    public void send(Player player, ProposalDraft draft) {
        DuelOfferBook.Outcome outcome;
        if (!draft.isCounter()) {
            Player target = ports.players().apply(draft.receiver());
            Optional<String> refusal = inviteRefusal(player, target, draft.receiverName());
            if (refusal.isPresent()) {
                tell(player, refusal.get());
                return;
            }
            outcome = service.offers().invite(player.getUniqueId(), draft.receiver(), bookSettings(draft.settings()),
                    draft.contract(), service.now());
        } else {
            outcome = service.offers().counter(player.getUniqueId(), draft.answeringOfferId(), draft.contract(), service.now());
        }
        if (!outcome.ok()) {
            boolean keepOpen = outcome.status() == DuelOfferBook.Status.INVALID_CONTRACT;
            refused(player, outcome, draft.receiverName(), draft.side() == DuelSide.CHALLENGER);
            if (!keepOpen) dropDraft(player, true);
            return;
        }
        dropDraft(player, true);
        announce(outcome.negotiation(), outcome.offer(), draft.arenaName());
    }

    /**
     * The settings the offer book gets: the arena's, with the stake ratio left to the API. The plugin can't value
     * staked items yet (no blueprint base price on its side), so P3's check would value every item at 0 and refuse
     * DESIGN §6.2's own example ("my sword, your 500 coins"). The API values items and applies the ratio in the
     * editor's advisory check and, binding, at lock-in. Reversible once the plugin can value items.
     */
    static DuelArenaSettings bookSettings(DuelArenaSettings s) {
        if (s.minStakeRatioPercent() == 0) return s;
        return new DuelArenaSettings(s.arenaId(), s.maxOffers(), s.offerSeconds(), s.allowCoinStakes(),
                s.allowItemStakes(), s.maxCoinStake(), s.maxItemStacks(), 0, s.feePercent(), s.allowedGearModes(),
                s.defaultGearMode(), s.eligibleKitIds(), s.warmupSeconds(), s.countdownSeconds(),
                s.fightTimeLimitSeconds(), s.maxWaitSeconds(), s.winnerLingerSeconds(), s.maxQueuedMatches());
    }

    private void refused(Player player, DuelOfferBook.Outcome outcome, String otherName, boolean authorIsChallenger) {
        if (outcome.status() == DuelOfferBook.Status.INVALID_CONTRACT) {
            tell(player, "The offer wasn't sent:");
            for (StakeViolation v : outcome.violations()) {
                tell(player, "• " + DuelChat.violation(v, player.getName(), otherName, authorIsChallenger));
            }
        } else if (outcome.status() == DuelOfferBook.Status.ON_COOLDOWN) {
            tell(player, "Wait " + outcome.cooldownSeconds() + " s before sending another duel invite.");
        } else {
            tell(player, DuelTexts.offerStatus(outcome.status()));
        }
    }

    /** The chat offer to its receiver (id-stamped buttons) and the confirmation to its author. */
    private void announce(DuelNegotiation negotiation, DuelOffer offer, String arenaName) {
        UUID author = negotiation.playerOf(offer.author());
        UUID receiver = negotiation.playerOf(offer.receiver());
        warned.remove(negotiation.negotiationId());
        knownNegotiators.add(author);
        knownNegotiators.add(receiver);
        StakeContract contract = offer.contract();
        String kit = contract.fixedKitId() == null ? null : kitName(contract.fixedKitId());
        int seconds = negotiation.settings().offerSeconds();
        Player receiverPlayer = ports.players().apply(receiver);
        if (receiverPlayer != null) {
            receiverPlayer.sendMessage(DuelChat.offer(new DuelChat.OfferText(offer.offerId(), nameOf(author), offer.number() > 1,
                    arenaName, offer.number(), negotiation.settings().maxOffers(), seconds, contract.gearMode(), kit,
                    stakeText(contract, offer.receiver()), stakeText(contract, offer.author()), offer.canBeCountered())));
        }
        Player authorPlayer = ports.players().apply(author);
        if (authorPlayer != null) {
            authorPlayer.sendMessage(DuelChat.sent(nameOf(receiver), offer.number(), negotiation.settings().maxOffers(),
                    seconds, contract.gearMode(), kit));
        }
    }

    /** The 1 s UI ticker: the expiry warning 10 s before a turn runs out. */
    public void tick() {
        long now = service.now();
        Set<Long> seen = new HashSet<>();
        for (Player player : onlineNegotiators()) {
            for (DuelNegotiation n : service.offers().negotiationsOf(player.getUniqueId())) {
                if (!seen.add(n.negotiationId()) || n.state() != DuelNegotiation.State.AWAITING_RESPONSE) continue;
                DuelOffer offer = n.currentOffer();
                if (n.settings().offerSeconds() <= DuelChat.EXPIRY_WARNING_SECONDS) continue;
                int left = DuelTicks.secondsUntil(offer.expiresAtTick(), now);
                if (left <= 0 || left > DuelChat.EXPIRY_WARNING_SECONDS) continue;
                if (Objects.equals(warned.get(n.negotiationId()), offer.offerId())) continue;
                warned.put(n.negotiationId(), offer.offerId());
                Player awaited = ports.players().apply(n.awaitedPlayer());
                if (awaited != null) {
                    awaited.sendMessage(DuelChat.expiryWarning(offer.offerId(), nameOf(n.playerOf(offer.author())), left,
                            offer.canBeCountered()));
                }
            }
        }
        warned.keySet().removeIf(id -> service.offers().negotiation(id) == null);
    }

    private List<Player> onlineNegotiators() {
        List<Player> out = new ArrayList<>();
        Set<UUID> ids = new HashSet<>(drafts.keySet());
        ids.addAll(knownNegotiators);
        for (UUID id : ids) {
            Player p = ports.players().apply(id);
            if (p != null && p.isOnline()) out.add(p);
        }
        knownNegotiators.removeIf(id -> !service.offers().isNegotiating(id));
        return out;
    }

    /** Everyone who sent or received an offer through the UI (the offer book has no "all open" query). */
    private final Set<UUID> knownNegotiators = new HashSet<>();

    // ==================== The editor ====================

    private void openEditor(Player player, ProposalDraft draft) {
        drafts.put(player.getUniqueId(), draft);
        knownNegotiators.add(draft.author());
        knownNegotiators.add(draft.receiver());
        openEditors.put(player.getUniqueId(), editor.open(player, draft));
        changed(player, draft);
    }

    /** Re-open the editor after an anvil or the kit picker, if its negotiation is still on. */
    private void reopen(Player player, ProposalDraft draft) {
        if (drafts.get(player.getUniqueId()) != draft || !player.isOnline()) return;
        if (draft.isCounter() && service.offers().byCurrentOfferId(draft.answeringOfferId()) == null) {
            drafts.remove(player.getUniqueId());
            tell(player, "That duel offer is no longer open.");
            return;
        }
        openEditors.put(player.getUniqueId(), editor.open(player, draft));
    }

    @Override
    public void cancel(Player player, ProposalDraft draft) {
        dropDraft(player, true);
        tell(player, draft.isCounter() ? "Counter not sent; the offer stays open until it expires."
                : "Duel invite not sent.");
    }

    @Override
    public void typeAmount(Player player, ProposalDraft draft, DuelSide side) {
        String whose = side == draft.side() ? "your" : draft.receiverName() + "'s";
        ports.textInput().capture(player, "Type " + whose + " coin stake (0–"
                        + DuelChat.coins(draft.settings().maxCoinStake()) + ") in the anvil and click the result.",
                text -> {
                    try {
                        long amount = Long.parseLong(text.trim().replace(",", "").replace("_", ""));
                        if (amount < 0) throw new NumberFormatException();
                        draft.setCoins(side, amount);
                    } catch (NumberFormatException bad) {
                        tell(player, "That isn't a number of coins.");
                    }
                    ports.mainThread().execute(() -> {
                        reopen(player, draft);
                        changed(player, draft);
                    });
                },
                () -> ports.mainThread().execute(() -> reopen(player, draft)));
    }

    @Override
    public void pickKit(Player player, ProposalDraft draft) {
        ports.nextTick().accept(() -> {
            if (drafts.get(player.getUniqueId()) == draft && player.isOnline()) kitPicker.openForDraft(player, draft);
        });
    }

    @Override
    public void changed(Player player, ProposalDraft draft) {
        if (ports.api() == null || draft.contract().isFriendly() || draft.checkInFlight || !draft.needsCheck()) return;
        Integer authorUser = ports.userIds().apply(draft.author());
        Integer receiverUser = ports.userIds().apply(draft.receiver());
        if (authorUser == null || receiverUser == null) return;
        int challengerUser = draft.side() == DuelSide.CHALLENGER ? authorUser : receiverUser;
        int opponentUser = draft.side() == DuelSide.CHALLENGER ? receiverUser : authorUser;
        StakeContract contract = draft.contract();
        List<ItemSummary> summaries = new ArrayList<>();
        for (DuelSide side : DuelSide.values()) {
            int owner = side == DuelSide.CHALLENGER ? challengerUser : opponentUser;
            for (ProposalDraft.Item item : draft.items(side)) {
                if (item.copy() != null) summaries.add(ports.items().summary(item.copy(), owner));
            }
        }
        LockRequest request = new LockRequest(UUID.randomUUID(), draft.settings().arenaId(), challengerUser, opponentUser,
                contract.gearMode(), contract.fixedKitId(), contract.challenger().coins(), contract.opponent().coins(),
                summaries, draft.offerNumber(), Instant.now());
        int version = draft.version();
        draft.checkInFlight = true;
        CompletableFuture<CheckResult> check;
        try {
            check = ports.api().check(request);
        } catch (RuntimeException e) {
            check = CompletableFuture.failedFuture(e);
        }
        if (check == null) check = CompletableFuture.failedFuture(new IllegalStateException("no check result"));
        check.whenComplete((result, error) -> ports.mainThread().execute(() -> {
            draft.checkInFlight = false;
            if (error == null && result != null) draft.checked(version, result);
            DuelProposalEditor.Holder open = openEditors.get(player.getUniqueId());
            if (open != null && open.draft() == draft && isOpen(player, open)) editor.render(open);
            if (error == null && draft.needsCheck() && drafts.get(player.getUniqueId()) == draft) changed(player, draft);
        }));
    }

    private void dropDraft(Player player, boolean close) {
        drafts.remove(player.getUniqueId());
        DuelProposalEditor.Holder open = openEditors.remove(player.getUniqueId());
        if (close && open != null && isOpen(player, open)) player.closeInventory();
    }

    /** The editor inventory closed (Escape): the draft is dropped unless an anvil or a picker took over. */
    public void editorClosed(Player player, DuelProposalEditor.Holder holder) {
        if (openEditors.get(player.getUniqueId()) == holder) openEditors.remove(player.getUniqueId());
        ProposalDraft draft = holder.draft();
        if (draft.reopening || drafts.get(player.getUniqueId()) != draft) return;
        drafts.remove(player.getUniqueId());
    }

    // ==================== The kit picker ====================

    @Override
    public void pickedForDraft(Player player, ProposalDraft draft, int kitId) {
        draft.selectKit(kitId);
        ports.nextTick().accept(() -> {
            reopen(player, draft);
            changed(player, draft);
        });
    }

    @Override
    public void back(Player player, ProposalDraft draft) {
        ports.nextTick().accept(() -> reopen(player, draft));
    }

    /** The kit picker closed without a pick (Escape): back to the editor, like Back. */
    public void kitPickerClosed(Player player, DuelKitPicker.Holder holder) {
        ProposalDraft draft = holder.draft();
        if (draft == null || drafts.get(player.getUniqueId()) != draft) return;
        ports.nextTick().accept(() -> {
            if (!(topHolder(player) instanceof DuelProposalEditor.Holder)) {
                reopen(player, draft);
            }
        });
    }

    @Override
    public void pickedInWarmup(Player player, int kitId) {
        kit(player, String.valueOf(kitId));
    }

    /** {@code /duel kit [kit]}: the warmup picker, or a pick by id or name (KIT_CHOICE, DESIGN §8.4). */
    public void kit(Player player, String arg) {
        Optional<DuelMatch> found = service.matchOf(player.getUniqueId());
        if (found.isEmpty()) {
            tell(player, "You can pick a kit only in the warmup of a kit-choice duel.");
            return;
        }
        DuelMatch match = found.get();
        if (match.gearMode() != GearMode.KIT_CHOICE) {
            tell(player, "This duel isn't a kit-choice duel.");
            return;
        }
        if (match.phase() != DuelPhase.WARMUP) {
            tell(player, match.phase().ordinal() > DuelPhase.WARMUP.ordinal()
                    ? "The kits are locked now." : "You can pick a kit once you are in the arena.");
            return;
        }
        remember(match.arena());
        List<Integer> kits = match.settings().eligibleKitIds();
        DuelSide side = match.sideOf(player.getUniqueId()).orElseThrow();
        if (arg == null || arg.isBlank()) {
            openWarmupPicker(player, match, side);
            return;
        }
        Integer kitId = parseKit(arg, kits);
        if (kitId == null) {
            tell(player, "Unknown kit. Pick one of: " + String.join(", ", kits.stream().map(this::kitName).toList()) + ".");
            return;
        }
        DuelStateMachine.KitChoice choice = service.chooseKit(player, kitId);
        switch (choice) {
            case CHOSEN -> {
                if (topHolder(player) instanceof DuelKitPicker.Holder) {
                    openWarmupPicker(player, match, side);
                }
            }
            case NOT_IN_WARMUP -> tell(player, "The kits are locked now.");
            case NOT_ELIGIBLE -> tell(player, "That kit isn't one of this arena's kits.");
            case NOT_KIT_CHOICE -> tell(player, "This duel isn't a kit-choice duel.");
        }
    }

    private void openWarmupPicker(Player player, DuelMatch match, DuelSide side) {
        DuelSide other = side.other();
        kitPicker.openInWarmup(player, match.settings().eligibleKitIds(), match.kitOf(side), match.playerOf(other),
                match.nameOf(other), match.hasPickedKit(other) ? match.kitOf(other) : null,
                Math.max(0, match.secondsRemaining(service.now())));
    }

    private Integer parseKit(String arg, List<Integer> kits) {
        String wanted = arg.trim();
        try {
            int id = Integer.parseInt(wanted);
            if (kits.contains(id)) return id;
        } catch (NumberFormatException notANumber) {
            // a name
        }
        for (int id : kits) {
            if (kitName(id).equalsIgnoreCase(wanted)) return id;
        }
        return null;
    }

    // ==================== /duel draw | toggle | claim ====================

    /** {@code /duel draw}: offer or agree a draw (FIGHT); the runtime tells the opponent. */
    public void draw(Player player) {
        DuelStateMachine.DrawOffer result = service.offerDraw(player);
        switch (result) {
            case OFFERED -> tell(player, "You offered a draw. If your opponent types /duel draw within "
                    + service.config().agreedDrawWindowSeconds() + " s, the duel ends as a draw.");
            case AGREED -> { } // the runtime announces the result
            case NOT_IN_FIGHT -> tell(player, "You can offer a draw only during the fight.");
        }
    }

    /** {@code /duel toggle}: refuse (or allow again) every duel invite; persisted. */
    public void toggle(Player player) {
        boolean off = ports.toggles() != null ? ports.toggles().toggle(player.getUniqueId())
                : !service.offers().invitesDisabled(player.getUniqueId());
        service.offers().setInvitesDisabled(player.getUniqueId(), off);
        tell(player, off ? "You now refuse every duel invite. /duel toggle allows them again."
                : "You accept duel invites again.");
    }

    /** {@code /duel claim}: what the escrow still owes this player (full inventory, offline payout). */
    public void claim(Player player) {
        if (service.isParticipating(player.getUniqueId())) {
            tell(player, "Claim your duel items after the duel.");
            return;
        }
        int owed = service.pendingClaims(player.getUniqueId());
        if (owed == 0) {
            tell(player, "You have no duel items to claim.");
            return;
        }
        DuelEscrowSpool.Delivery delivery = service.claim(player);
        if (delivery.gaveAnything()) {
            tell(player, "Claimed " + (delivery.toInventory() + delivery.toEnderChest()) + " stack(s)"
                    + (delivery.toEnderChest() > 0 ? ", " + delivery.toEnderChest() + " into your ender chest" : "") + ".");
        }
        if (delivery.stillOwed() > 0) {
            tell(player, delivery.stillOwed() + " stack(s) still wait for you; make room and type /duel claim again.");
        } else if (!delivery.gaveAnything()) {
            tell(player, "Nothing could be handed over right now; try again in a moment.");
        }
    }

    // ==================== Join, quit, disable ====================

    public void onJoin(Player player) {
        if (ports.toggles() != null) {
            service.offers().setInvitesDisabled(player.getUniqueId(), ports.toggles().isDisabled(player.getUniqueId()));
        }
    }

    public void onQuit(Player player) {
        drafts.remove(player.getUniqueId());
        openEditors.remove(player.getUniqueId());
        knownNegotiators.remove(player.getUniqueId());
    }

    /** Plugin disable: close every duel menu. */
    public void closeAll() {
        for (UUID id : List.copyOf(openEditors.keySet())) {
            Player p = ports.players().apply(id);
            if (p != null) p.closeInventory();
        }
        drafts.clear();
        openEditors.clear();
    }

    // ==================== DuelObserver ====================

    @Override
    public void negotiationsClosed(List<DuelOfferBook.Closed> closed) {
        for (DuelOfferBook.Closed c : closed) {
            DuelNegotiation n = c.negotiation();
            closed(n);
            closeMenusOf(n);
            switch (c.reason()) {
                case EXPIRED -> {
                    UUID awaited = n.playerOf(n.currentOffer().receiver());
                    tellPlayer(awaited, "The duel offer from " + nameOf(otherOf(n, awaited)) + " expired.");
                    tellPlayer(otherOf(n, awaited), nameOf(awaited) + " didn't answer your duel offer in time.");
                }
                case PLAYER_GONE -> {
                    UUID gone = c.closedBy();
                    if (gone != null) tellPlayer(otherOf(n, gone), "The duel with " + nameOf(gone) + " is off: they left.");
                }
                case SUPERSEDED -> {
                    for (UUID p : List.of(n.inviter(), n.invitee())) {
                        tellPlayer(p, "The duel offer between " + nameOf(n.inviter()) + " and " + nameOf(n.invitee())
                                + " is off: one of you started another duel.");
                    }
                }
                case DECLINED, CANCELLED, LOCKED, LOCK_REFUSED -> { }
            }
        }
    }

    @Override
    public void lockRefused(DuelNegotiation negotiation, String message) {
        refusalTold.add(negotiation.negotiationId());
    }

    @Override
    public void warmupStarted(DuelMatch match, int warmupSeconds) {
        if (match.gearMode() != GearMode.KIT_CHOICE) return;
        remember(match.arena());
        for (DuelSide side : DuelSide.values()) {
            Player p = ports.players().apply(match.playerOf(side));
            if (p == null) continue;
            DuelSide other = side.other();
            p.sendMessage(DuelChat.kitPrompt(match.settings().eligibleKitIds(), this::kitName, match.kitOf(side),
                    match.nameOf(other), match.hasPickedKit(other) ? match.kitOf(other) : null));
        }
    }

    @Override
    public void kitChosen(DuelMatch match, DuelSide side, int kitId) {
        Player chooser = ports.players().apply(match.playerOf(side));
        if (chooser != null) tell(chooser, "You will fight with " + kitName(kitId) + ".");
        Player other = ports.players().apply(match.playerOf(side.other()));
        if (other != null) {
            tell(other, match.nameOf(side) + " picked " + kitName(kitId) + ".");
            if (topHolder(other) instanceof DuelKitPicker.Holder h && h.draft() == null) {
                openWarmupPicker(other, match, side.other());
            }
        }
    }

    @Override
    public void phaseChanged(DuelMatch match, DuelPhase from, DuelPhase to) {
        if (from != DuelPhase.WARMUP || match.gearMode() != GearMode.KIT_CHOICE) return;
        for (DuelSide side : DuelSide.values()) {
            Player p = ports.players().apply(match.playerOf(side));
            if (p != null && topHolder(p) instanceof DuelKitPicker.Holder h
                    && h.draft() == null) {
                p.closeInventory();
                tell(p, "The kits are locked: you fight with " + kitName(match.kitOf(side)) + ".");
            }
        }
    }

    // ==================== Helpers ====================

    private void closed(DuelNegotiation negotiation) {
        warned.remove(negotiation.negotiationId());
    }

    /** Close the editors and views that belong to a negotiation that ended or moved on. */
    private void closeMenusOf(DuelNegotiation negotiation) {
        for (UUID id : List.of(negotiation.inviter(), negotiation.invitee())) {
            Player p = ports.players().apply(id);
            ProposalDraft draft = drafts.get(id);
            if (draft != null && draft.isCounter() && draft.negotiationId() == negotiation.negotiationId()) {
                drafts.remove(id);
                DuelProposalEditor.Holder open = openEditors.remove(id);
                if (p != null && open != null && isOpen(p, open)) p.closeInventory();
            }
            if (p != null && topHolder(p) instanceof DuelOfferView.Holder view
                    && negotiation.offers().stream().anyMatch(o -> o.offerId() == view.offerId())) {
                p.closeInventory();
            }
        }
    }

    private static boolean isOpen(Player player, InventoryHolder holder) {
        return topHolder(player) == holder;
    }

    /** The holder of the player's open top inventory, or null. */
    private static InventoryHolder topHolder(Player player) {
        var view = player.getOpenInventory();
        if (view == null || view.getTopInventory() == null) return null;
        return view.getTopInventory().getHolder(false);
    }

    private DuelNegotiation negotiationWithOffer(UUID player, long offerId) {
        for (DuelNegotiation n : service.offers().negotiationsOf(player)) {
            if (n.offers().stream().anyMatch(o -> o.offerId() == offerId)) return n;
        }
        return null;
    }

    private static UUID otherOf(DuelNegotiation n, UUID player) {
        return n.inviter().equals(player) ? n.invitee() : n.inviter();
    }

    private String stakeText(StakeContract contract, DuelSide side) {
        return DuelChat.stake(contract.stakeOf(side).coins(), contract.stakeOf(side).items().stream()
                .map(this::describe).toList());
    }

    private String describe(StakeItem item) {
        return copyOf(item.refId()).map(ports.gui()::describe)
                .orElse(DuelGui.materialName(item.material()) + (item.amount() > 1 ? " x" + item.amount() : ""));
    }

    private Optional<ItemStack> copyOf(String refId) {
        return service.stakeItems().get(refId).map(DuelStakeItems.Ref::copy);
    }

    private String nameOf(UUID player) {
        Player p = ports.players().apply(player);
        return p != null ? p.getName() : "your opponent";
    }

    String kitName(Integer kitId) {
        if (kitId == null) return "no kit";
        return kitNames.getOrDefault(kitId, "Kit #" + kitId);
    }

    private String arenaName(int arenaId) {
        return arenaNames.getOrDefault(arenaId, "the arena");
    }

    private void remember(KnkArena arena) {
        if (arena == null) return;
        arenaNames.put(arena.id(), arena.name() == null ? "the arena" : arena.name());
        for (KnkArenaKit kit : arena.eligibleKits()) {
            if (kit.kitName() != null) kitNames.put(kit.kitId(), kit.kitName());
        }
    }

    /** Note an arena's kit and arena names (the commands call this with the cached arena). */
    public void rememberArena(KnkArena arena) {
        remember(arena);
    }

    private Set<UUID> toggles() {
        return ports.toggles() == null ? Set.of() : ports.toggles().disabled();
    }

    private void tellPlayer(UUID id, String message) {
        Player p = ports.players().apply(id);
        if (p != null) tell(p, message);
    }

    private static void tell(Player player, String message) {
        player.sendMessage(Component.text(message, DuelChat.TEXT));
    }
}
