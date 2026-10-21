package net.knightsandkings.knk.paper.duel.ui;

import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.CheckResult;
import net.knightsandkings.knk.core.duel.DuelArenaSettings;
import net.knightsandkings.knk.core.duel.DuelNegotiation;
import net.knightsandkings.knk.core.duel.DuelOffer;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.Stake;
import net.knightsandkings.knk.core.duel.StakeContract;
import net.knightsandkings.knk.core.duel.StakeContractValidator;
import net.knightsandkings.knk.core.duel.StakeItem;
import net.knightsandkings.knk.core.duel.StakeViolation;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * What the proposal editor is building (arena duels DESIGN §6.2, §6.4): one complete contract by one author, either
 * offer #1 or a counter to the current offer. Items never move (D3): an added item is a reference to a stack in the
 * author's own inventory, registered with the runtime's {@code DuelStakeItems}. Only the owner adds items to their
 * half; the author may remove the other side's items. The gear mode can only be one the arena allows.
 * <p>
 * Pure state plus the rules the editor needs before sending; the offer book checks the same rules again when the
 * offer is sent, and lock-in checks them for real. Main thread.
 */
public final class ProposalDraft {

    /** A staked stack: the contract's reference, a copy for the icon, and the inventory slot it came from (-1 unknown). */
    public record Item(StakeItem ref, ItemStack copy, int slot) {
        public Item {
            Objects.requireNonNull(ref, "ref");
        }
    }

    /** Registers an own stack for an offer (the runtime's {@code DuelStakeItems.register}). */
    @FunctionalInterface
    public interface Registrar {
        StakeItem register(UUID owner, ItemStack stack, int slot);
    }

    private final long negotiationId;
    private final long answeringOfferId;
    private final UUID author;
    private final UUID receiver;
    private final String authorName;
    private final String receiverName;
    private final DuelSide side;
    private final DuelArenaSettings settings;
    private final String arenaName;
    private final StakeContract previous;
    private final int offerNumber;
    private GearMode gearMode;
    private Integer fixedKitId;
    private final Map<DuelSide, Long> coins = new EnumMap<>(DuelSide.class);
    private final Map<DuelSide, List<Item>> items = new EnumMap<>(DuelSide.class);

    /** The API's advisory check of the current contract (eligibility, limits, fee); null until it answered. */
    private CheckResult apiCheck;
    private int version;
    private int checkedVersion = -1;
    boolean checkInFlight;
    /** The editor closes because an anvil (or a picker) opens on top of it: keep the draft. */
    boolean reopening;

    private ProposalDraft(long negotiationId, long answeringOfferId, UUID author, String authorName, UUID receiver,
                          String receiverName, DuelSide side, DuelArenaSettings settings, String arenaName,
                          StakeContract previous, int offerNumber) {
        this.negotiationId = negotiationId;
        this.answeringOfferId = answeringOfferId;
        this.author = Objects.requireNonNull(author, "author");
        this.authorName = Objects.requireNonNull(authorName, "authorName");
        this.receiver = Objects.requireNonNull(receiver, "receiver");
        this.receiverName = Objects.requireNonNull(receiverName, "receiverName");
        this.side = Objects.requireNonNull(side, "side");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.arenaName = arenaName == null ? "the arena" : arenaName;
        this.previous = previous;
        this.offerNumber = offerNumber;
        for (DuelSide s : DuelSide.values()) {
            coins.put(s, 0L);
            items.put(s, new ArrayList<>());
        }
    }

    /** Offer #1 by the inviter: no stakes, the arena's default gear mode (and default kit for {@code FIXED_KIT}). */
    public static ProposalDraft invite(UUID inviter, String inviterName, UUID invitee, String inviteeName,
                                       DuelArenaSettings settings, String arenaName) {
        ProposalDraft draft = new ProposalDraft(0, 0, inviter, inviterName, invitee, inviteeName, DuelSide.CHALLENGER,
                settings, arenaName, null, 1);
        draft.applyMode(settings.defaultGearMode(), null);
        return draft;
    }

    /**
     * A counter to {@code negotiation}'s current offer by its receiver, pre-filled with that offer.
     *
     * @param known the icon copy and slot hint of a referenced stack ({@code DuelStakeItems.get(refId)}), empty
     *              when unknown
     */
    public static ProposalDraft counter(DuelNegotiation negotiation, String authorName, String receiverName,
                                        String arenaName, Function<String, Optional<Item>> known) {
        DuelOffer current = Objects.requireNonNull(negotiation.currentOffer(), "current offer");
        DuelSide side = current.receiver();
        ProposalDraft draft = new ProposalDraft(negotiation.negotiationId(), current.offerId(),
                negotiation.playerOf(side), authorName, negotiation.playerOf(side.other()), receiverName, side,
                negotiation.settings(), arenaName, current.contract(), negotiation.offerCount() + 1);
        StakeContract contract = current.contract();
        draft.applyMode(contract.gearMode(), contract.fixedKitId());
        for (DuelSide s : DuelSide.values()) {
            Stake stake = contract.stakeOf(s);
            draft.coins.put(s, stake.coins());
            for (StakeItem ref : stake.items()) {
                Optional<Item> found = known.apply(ref.refId());
                draft.items.get(s).add(new Item(ref, found.map(Item::copy).orElse(null), found.map(Item::slot).orElse(-1)));
            }
        }
        return draft;
    }

    // ==================== Gear mode and kit ====================

    /** The arena's allowed gear modes, in the fixed order of {@link GearMode}. */
    public List<GearMode> allowedModes() {
        List<GearMode> out = new ArrayList<>();
        for (GearMode mode : GearMode.values()) if (settings.allows(mode)) out.add(mode);
        return List.copyOf(out);
    }

    /** The gear-mode button: the next allowed mode. False when the arena allows only one (a fixed label). */
    public boolean cycleGearMode() {
        List<GearMode> modes = allowedModes();
        if (modes.size() <= 1) return false;
        int at = modes.indexOf(gearMode);
        applyMode(modes.get((at + 1) % modes.size()), fixedKitId);
        changed();
        return true;
    }

    /** Select {@code mode}; refused (false, nothing changes) when the arena doesn't allow it. */
    public boolean selectGearMode(GearMode mode) {
        if (mode == null || !settings.allows(mode)) return false;
        if (mode == gearMode) return true;
        applyMode(mode, fixedKitId);
        changed();
        return true;
    }

    /** The {@code FIXED_KIT} picker: only an eligible kit, only in that mode. */
    public boolean selectKit(int kitId) {
        if (gearMode != GearMode.FIXED_KIT || !settings.isKitEligible(kitId)) return false;
        if (!Objects.equals(fixedKitId, kitId)) {
            fixedKitId = kitId;
            changed();
        }
        return true;
    }

    private void applyMode(GearMode mode, Integer kit) {
        GearMode chosen = settings.allows(mode) ? mode : allowedModes().getFirst();
        gearMode = chosen;
        if (chosen == GearMode.FIXED_KIT) {
            fixedKitId = kit != null && settings.isKitEligible(kit) ? kit : settings.defaultKitId();
        } else {
            fixedKitId = null;
        }
    }

    // ==================== Items ====================

    /**
     * A click on a stack in the author's own (bottom) inventory: adds a reference to their half. Nothing moves.
     *
     * @param refusal why a stack can't be staked (the runtime's {@code DuelItems.stakeRefusal}), empty when it can
     * @return why it wasn't added, or empty
     */
    public Optional<String> addOwnItem(int slot, ItemStack stack, Function<ItemStack, Optional<String>> refusal,
                                       Registrar registrar) {
        if (stack == null || stack.isEmpty() || stack.getAmount() < 1) return Optional.of("Pick an item to stake.");
        if (!settings.allowItemStakes()) return Optional.of("This arena doesn't allow item stakes.");
        List<Item> mine = items.get(side);
        if (mine.size() >= settings.maxItemStacks()) {
            return Optional.of("You can stake at most " + settings.maxItemStacks() + " stacks.");
        }
        for (Item item : mine) {
            if (slot >= 0 && item.slot() == slot) return Optional.of("That stack is already in your stake.");
        }
        Optional<String> refused = refusal.apply(stack);
        if (refused.isPresent()) return refused;
        StakeItem ref = registrar.register(author, stack, slot);
        mine.add(new Item(ref, stack.clone(), slot));
        changed();
        return Optional.empty();
    }

    /** A click on an icon in either half removes it (the author may remove the other side's items too, §6.2). */
    public boolean removeItem(DuelSide from, int index) {
        List<Item> list = items.get(from);
        if (index < 0 || index >= list.size()) return false;
        list.remove(index);
        changed();
        return true;
    }

    // ==================== Coins ====================

    /** A coin button: {@code delta} on {@code who}'s stake, kept within 0..MaxCoinStake. False when nothing changed. */
    public boolean adjustCoins(DuelSide who, long delta) {
        return setCoins(who, coins.get(who) + delta);
    }

    /** The anvil input: an exact amount, kept within 0..MaxCoinStake. False when coins aren't allowed or unchanged. */
    public boolean setCoins(DuelSide who, long amount) {
        if (!settings.allowCoinStakes()) return false;
        long clamped = Math.max(0, Math.min(settings.maxCoinStake(), amount));
        if (clamped == coins.get(who)) return false;
        coins.put(who, clamped);
        changed();
        return true;
    }

    // ==================== The contract ====================

    public StakeContract contract() {
        return new StakeContract(gearMode, fixedKitId, stake(DuelSide.CHALLENGER), stake(DuelSide.OPPONENT));
    }

    private Stake stake(DuelSide who) {
        return new Stake(coins.get(who), items.get(who).stream().map(Item::ref).toList());
    }

    /**
     * The offer book's rules for this offer (DESIGN §6.3, advisory here): shown as warnings in the editor. With items
     * staked, the stake ratio is the API's to judge (it values items; the plugin can't yet, KNG-224), so it is left out here.
     */
    public List<StakeViolation> violations(Predicate<StakeItem> blocked, ToLongFunction<StakeItem> itemValue) {
        StakeContract contract = contract();
        boolean withItems = !contract.challenger().items().isEmpty() || !contract.opponent().items().isEmpty();
        return StakeContractValidator.validateOffer(previous, contract, side, settings, blocked, itemValue).stream()
                .filter(v -> !(withItems && v.code() == StakeViolation.Code.STAKE_RATIO))
                .toList();
    }

    /** The arena fee on a decisive result with these coins (D15). */
    public long fee() {
        return settings.feeFor(contract().potCoins());
    }

    private void changed() {
        version++;
    }

    // ==================== The API's advisory check ====================

    public int version() {
        return version;
    }

    /** Whether the API's check is older than the contract. */
    public boolean needsCheck() {
        return checkedVersion != version;
    }

    public CheckResult apiCheck() {
        return checkedVersion == version ? apiCheck : null;
    }

    void checked(int forVersion, CheckResult result) {
        if (forVersion != version) return;
        checkedVersion = forVersion;
        apiCheck = result;
    }

    // ==================== Getters ====================

    /** 0 for offer #1 (no negotiation yet). */
    public long negotiationId() {
        return negotiationId;
    }

    /** The offer this counters, 0 for offer #1. */
    public long answeringOfferId() {
        return answeringOfferId;
    }

    public boolean isCounter() {
        return previous != null;
    }

    public UUID author() {
        return author;
    }

    public UUID receiver() {
        return receiver;
    }

    public String authorName() {
        return authorName;
    }

    public String receiverName() {
        return receiverName;
    }

    /** The author's side. */
    public DuelSide side() {
        return side;
    }

    public DuelArenaSettings settings() {
        return settings;
    }

    public String arenaName() {
        return arenaName;
    }

    public int offerNumber() {
        return offerNumber;
    }

    public boolean isLastOffer() {
        return offerNumber >= settings.maxOffers();
    }

    public GearMode gearMode() {
        return gearMode;
    }

    public Integer fixedKitId() {
        return fixedKitId;
    }

    public long coins(DuelSide who) {
        return coins.get(who);
    }

    public List<Item> items(DuelSide who) {
        return List.copyOf(items.get(who));
    }
}
