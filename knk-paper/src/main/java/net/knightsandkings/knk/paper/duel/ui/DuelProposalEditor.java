package net.knightsandkings.knk.paper.duel.ui;

import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.CheckResult;
import net.knightsandkings.knk.core.domain.duels.KnkDuelRecords.Refusal;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.StakeItem;
import net.knightsandkings.knk.core.duel.StakeViolation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * The proposal editor (arena duels DESIGN §6.4): a hand-rolled 54-slot inventory with its own {@link Holder}. Every
 * click is cancelled by {@code DuelMenuListener}, so no item ever enters or leaves it; a click on a stack in the
 * player's own (bottom) inventory adds a reference to their half, and the stack stays where it is (D3).
 *
 * <pre>
 * row 0    [you]  .  [GEAR MODE ▸]  [KIT ▸]  [arena]  [fee]  [warnings]  .  [them]
 * row 1-3  your stake: 12 icons (click = remove)  |  their stake: 12 icons (click = remove)
 * row 4    [−100] [−10] [+10] [+100]  |  [−100] [−10] [+10] [+100]
 * row 5    [Cancel]  .  [your coins: type]  .  [Send offer n/max]  .  [their coins: type]
 * </pre>
 * The layout is DESIGN §6.4's, rearranged so each side's coin buttons and up to 12 item stacks fit in 9 columns.
 */
public final class DuelProposalEditor {

    public static final int SIZE = 54;
    public static final int YOU = 0;
    public static final int GEAR = 2;
    public static final int KIT = 3;
    public static final int ARENA = 4;
    public static final int FEE = 5;
    public static final int WARNINGS = 6;
    public static final int THEM = 8;
    public static final int[] LEFT_ITEMS = {9, 10, 11, 12, 18, 19, 20, 21, 27, 28, 29, 30};
    public static final int[] RIGHT_ITEMS = {14, 15, 16, 17, 23, 24, 25, 26, 32, 33, 34, 35};
    public static final int[] DIVIDER = {13, 22, 31, 40};
    public static final int[] LEFT_COINS = {36, 37, 38, 39};
    public static final int[] RIGHT_COINS = {41, 42, 43, 44};
    public static final long[] COIN_DELTAS = {-100, -10, 10, 100};
    public static final int CANCEL = 45;
    public static final int YOUR_AMOUNT = 47;
    public static final int SEND = 49;
    public static final int THEIR_AMOUNT = 51;

    /** What the editor needs from the runtime and the item rules. */
    public record Context(
            DuelGui gui,
            IntFunction<String> kitName,
            Function<ItemStack, Optional<String>> stakeRefusal,
            ProposalDraft.Registrar registrar,
            Predicate<StakeItem> blocked,
            ToLongFunction<StakeItem> itemValue,
            Function<UUID, OptionalLong> coinBalance
    ) {
        public Context {
            Objects.requireNonNull(gui, "gui");
            kitName = kitName == null ? id -> "Kit #" + id : kitName;
            Objects.requireNonNull(stakeRefusal, "stakeRefusal");
            Objects.requireNonNull(registrar, "registrar");
            coinBalance = coinBalance == null ? id -> OptionalLong.empty() : coinBalance;
        }
    }

    /** The buttons that leave the editor or need another screen; the controller answers them. */
    public interface Actions {
        void send(Player player, ProposalDraft draft);

        void cancel(Player player, ProposalDraft draft);

        /** The anvil for an exact coin amount of {@code side}. */
        void typeAmount(Player player, ProposalDraft draft, DuelSide side);

        /** The {@code FIXED_KIT} picker. */
        void pickKit(Player player, ProposalDraft draft);

        /** The contract changed (or the player asked again): run the API's advisory check. */
        void changed(Player player, ProposalDraft draft);
    }

    /** The open editor of one player. */
    public static final class Holder implements InventoryHolder {
        private final ProposalDraft draft;
        private Inventory inventory;

        Holder(ProposalDraft draft) {
            this.draft = draft;
        }

        public ProposalDraft draft() {
            return draft;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Context context;
    private final Actions actions;

    public DuelProposalEditor(Context context, Actions actions) {
        this.context = Objects.requireNonNull(context, "context");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    /** Opens (or re-opens after an anvil or picker) the editor of {@code draft}. */
    public Holder open(Player player, ProposalDraft draft) {
        Holder holder = new Holder(draft);
        holder.inventory = context.gui().create(holder, SIZE, Component.text(
                (draft.isCounter() ? "Counter offer to " : "Duel offer to ") + draft.receiverName(), NamedTextColor.DARK_RED));
        render(holder);
        draft.reopening = false;
        player.openInventory(holder.inventory);
        return holder;
    }

    /**
     * A click while the editor is open; the listener has already cancelled it.
     *
     * @param top          the click was in the editor (else in the player's own inventory)
     * @param slot         the slot in the clicked inventory (for the bottom: the player inventory index)
     * @param bottomStack  the stack under the cursor in the player's inventory (bottom clicks)
     */
    public void click(Player player, Holder holder, boolean top, int slot, ItemStack bottomStack) {
        ProposalDraft draft = holder.draft();
        if (!top) {
            Optional<String> refused = draft.addOwnItem(slot, bottomStack, context.stakeRefusal(), context.registrar());
            if (refused.isPresent()) {
                if (bottomStack != null && !bottomStack.isEmpty()) player.sendActionBar(Component.text(refused.get(), DuelChat.BAD));
                return;
            }
            changed(player, holder);
            return;
        }
        DuelSide mine = draft.side();
        int left = indexOf(LEFT_ITEMS, slot);
        int right = indexOf(RIGHT_ITEMS, slot);
        int leftCoin = indexOf(LEFT_COINS, slot);
        int rightCoin = indexOf(RIGHT_COINS, slot);
        if (left >= 0) {
            if (draft.removeItem(mine, left)) changed(player, holder);
        } else if (right >= 0) {
            if (draft.removeItem(mine.other(), right)) changed(player, holder);
        } else if (leftCoin >= 0) {
            coins(player, holder, mine, COIN_DELTAS[leftCoin]);
        } else if (rightCoin >= 0) {
            coins(player, holder, mine.other(), COIN_DELTAS[rightCoin]);
        } else if (slot == GEAR) {
            if (draft.cycleGearMode()) {
                changed(player, holder);
            } else {
                player.sendActionBar(Component.text("This arena only allows "
                        + DuelChat.gearModeName(draft.gearMode()) + ".", DuelChat.MUTED));
            }
        } else if (slot == KIT) {
            if (draft.gearMode() == GearMode.FIXED_KIT) {
                draft.reopening = true;
                actions.pickKit(player, draft);
            }
        } else if (slot == YOUR_AMOUNT || slot == THEIR_AMOUNT) {
            if (!draft.settings().allowCoinStakes()) {
                player.sendActionBar(Component.text("This arena doesn't allow coin stakes.", DuelChat.MUTED));
                return;
            }
            draft.reopening = true;
            actions.typeAmount(player, draft, slot == YOUR_AMOUNT ? mine : mine.other());
        } else if (slot == WARNINGS) {
            actions.changed(player, draft);
        } else if (slot == CANCEL) {
            actions.cancel(player, draft);
        } else if (slot == SEND) {
            actions.send(player, draft);
        }
    }

    private void coins(Player player, Holder holder, DuelSide side, long delta) {
        if (!holder.draft().settings().allowCoinStakes()) {
            player.sendActionBar(Component.text("This arena doesn't allow coin stakes.", DuelChat.MUTED));
            return;
        }
        if (holder.draft().adjustCoins(side, delta)) changed(player, holder);
    }

    private void changed(Player player, Holder holder) {
        render(holder);
        actions.changed(player, holder.draft());
    }

    /** Draws every slot from the draft (also after the API's check answered). */
    public void render(Holder holder) {
        ProposalDraft d = holder.draft();
        Inventory inv = holder.inventory;
        DuelGui gui = context.gui();
        inv.clear();
        DuelSide mine = d.side();
        DuelSide theirs = mine.other();

        inv.setItem(YOU, gui.head(d.author(), Component.text("You (" + d.authorName() + ")", DuelChat.ACCENT),
                lore("Your stake: " + stakeText(d, mine))));
        inv.setItem(THEM, gui.head(d.receiver(), Component.text(d.receiverName(), DuelChat.ACCENT),
                lore("Their stake: " + stakeText(d, theirs))));
        inv.setItem(GEAR, gearIcon(gui, d));
        if (d.gearMode() == GearMode.FIXED_KIT) {
            inv.setItem(KIT, gui.icon(Material.IRON_CHESTPLATE, Component.text("Kit: "
                    + (d.fixedKitId() == null ? "none" : context.kitName().apply(d.fixedKitId())) + " ▸", NamedTextColor.AQUA),
                    lore("Click to pick the kit both of you", "fight with.")));
        }
        inv.setItem(ARENA, gui.icon(Material.IRON_SWORD, Component.text(d.arenaName(), DuelChat.ACCENT),
                lore("Offer " + d.offerNumber() + " of " + d.settings().maxOffers()
                        + (d.isLastOffer() ? " (the last: accept or decline only)" : ""),
                        "Each turn lasts " + d.settings().offerSeconds() + " s.")));
        inv.setItem(FEE, feeIcon(gui, d));
        inv.setItem(WARNINGS, warningsIcon(gui, d));
        for (int slot : DIVIDER) {
            inv.setItem(slot, gui.icon(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of()));
        }
        renderItems(inv, gui, d.items(mine), LEFT_ITEMS, "Click to remove it from your stake.");
        renderItems(inv, gui, d.items(theirs), RIGHT_ITEMS, "Click to remove it from their stake.");
        if (d.settings().allowCoinStakes()) {
            coinButtons(inv, gui, LEFT_COINS, "your");
            coinButtons(inv, gui, RIGHT_COINS, "their");
            OptionalLong balance = context.coinBalance().apply(d.author());
            List<Component> yourLore = new ArrayList<>(lore("Click to type an exact amount."));
            balance.ifPresent(b -> yourLore.add(Component.text("Your balance: " + DuelChat.coins(b)
                    + " coins (checked at lock-in)", DuelChat.MUTED)));
            inv.setItem(YOUR_AMOUNT, gui.icon(Material.GOLD_INGOT, Component.text("Your coins: "
                    + DuelChat.coins(d.coins(mine)), DuelChat.ACCENT), yourLore));
            inv.setItem(THEIR_AMOUNT, gui.icon(Material.GOLD_INGOT, Component.text(d.receiverName() + "'s coins: "
                    + DuelChat.coins(d.coins(theirs)), DuelChat.ACCENT), lore("Click to type an exact amount.",
                    "Max " + DuelChat.coins(d.settings().maxCoinStake()) + " per side.")));
        }
        inv.setItem(CANCEL, gui.icon(Material.BARRIER, Component.text("Cancel", DuelChat.BAD),
                lore(d.isCounter() ? "Close without answering; the offer stays open." : "Close without inviting.")));
        List<String> sendLore = new ArrayList<>();
        sendLore.add("Send this offer to " + d.receiverName() + ".");
        if (d.isLastOffer()) sendLore.add("It is the last offer: they can only accept or decline.");
        if (!violations(d).isEmpty()) sendLore.add("Fix the warnings first.");
        inv.setItem(SEND, gui.icon(Material.LIME_CONCRETE, Component.text("Send offer " + d.offerNumber() + " of "
                + d.settings().maxOffers(), DuelChat.GOOD), lore(sendLore.toArray(String[]::new))));
    }

    private ItemStack gearIcon(DuelGui gui, ProposalDraft d) {
        List<GearMode> modes = d.allowedModes();
        List<Component> lore = new ArrayList<>();
        for (String line : DuelChat.gearModeLore(d.gearMode())) {
            lore.add(Component.text(line, d.gearMode() == GearMode.OWN_GEAR_DROP ? DuelChat.BAD : NamedTextColor.WHITE));
        }
        lore.add(Component.empty());
        if (modes.size() > 1) {
            lore.add(Component.text("Click to cycle: ", DuelChat.MUTED).append(Component.text(String.join(" ▸ ",
                    modes.stream().map(DuelChat::gearModeName).toList()), DuelChat.MUTED)));
        } else {
            lore.add(Component.text("The only gear mode this arena allows.", DuelChat.MUTED));
        }
        Material icon = switch (d.gearMode()) {
            case OWN_GEAR_KEEP -> Material.DIAMOND_CHESTPLATE;
            case OWN_GEAR_DROP -> Material.TNT;
            case FIXED_KIT -> Material.CHEST;
            case KIT_CHOICE -> Material.ENDER_CHEST;
        };
        return gui.icon(icon, Component.text("Gear: " + DuelChat.gearModeName(d.gearMode()) + (modes.size() > 1 ? " ▸" : ""),
                d.gearMode() == GearMode.OWN_GEAR_DROP ? DuelChat.BAD : DuelChat.ACCENT,
                d.gearMode() == GearMode.OWN_GEAR_DROP ? TextDecoration.BOLD : TextDecoration.ITALIC), lore);
    }

    private ItemStack feeIcon(DuelGui gui, ProposalDraft d) {
        int percent = d.settings().feePercent();
        CheckResult check = d.apiCheck();
        long fee = check != null ? check.feeCoins() : d.fee();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(percent + "% of the coin pot on a decisive result: " + DuelChat.coins(fee) + " coins.",
                NamedTextColor.WHITE));
        lore.add(Component.text("No fee on draws, cancels or friendly duels.", DuelChat.MUTED));
        return gui.icon(Material.GOLD_NUGGET, Component.text("Arena fee: " + percent + "%", DuelChat.ACCENT), lore);
    }

    private ItemStack warningsIcon(DuelGui gui, ProposalDraft d) {
        List<Component> lore = new ArrayList<>();
        for (StakeViolation v : violations(d)) {
            lore.add(Component.text("• " + DuelChat.violation(v, d.authorName(), d.receiverName(),
                    d.side() == DuelSide.CHALLENGER), DuelChat.BAD));
        }
        boolean friendly = d.contract().isFriendly();
        CheckResult check = d.apiCheck();
        if (!friendly) {
            if (check == null) {
                lore.add(Component.text("Checking eligibility and limits…", DuelChat.MUTED));
            } else {
                for (Refusal refusal : check.refusals()) lore.add(Component.text("• " + refusal.message(), DuelChat.BAD));
            }
        }
        boolean clean = lore.stream().noneMatch(line -> DuelChat.BAD.equals(line.color()));
        if (clean) {
            lore.addFirst(Component.text(friendly ? "A friendly duel: no stakes, no fee, no checks."
                    : check == null ? "No problems so far." : "Eligibility and limits: OK.", DuelChat.GOOD));
        }
        lore.add(Component.empty());
        lore.add(Component.text("Lock-in checks everything again. Click to re-check.", DuelChat.MUTED));
        return gui.icon(clean ? Material.LIME_DYE : Material.RED_DYE,
                Component.text(clean ? "No warnings" : "Warnings", clean ? DuelChat.GOOD : DuelChat.BAD), lore);
    }

    private List<StakeViolation> violations(ProposalDraft d) {
        return d.violations(context.blocked(), context.itemValue() == null ? item -> 0 : context.itemValue());
    }

    private void renderItems(Inventory inv, DuelGui gui, List<ProposalDraft.Item> items, int[] slots, String hint) {
        for (int i = 0; i < items.size() && i < slots.length; i++) {
            inv.setItem(slots[i], gui.stakeIcon(items.get(i).copy(),
                    List.of(Component.text("Staked in this offer", DuelChat.ACCENT), Component.text(hint, DuelChat.MUTED))));
        }
    }

    private static void coinButtons(Inventory inv, DuelGui gui, int[] slots, String whose) {
        for (int i = 0; i < slots.length; i++) {
            long delta = COIN_DELTAS[i];
            inv.setItem(slots[i], gui.icon(delta < 0 ? Material.RED_STAINED_GLASS_PANE : Material.LIME_STAINED_GLASS_PANE,
                    Component.text((delta < 0 ? "−" : "+") + Math.abs(delta) + " coins", delta < 0 ? DuelChat.BAD : DuelChat.GOOD),
                    lore((delta < 0 ? "Lower " : "Raise ") + whose + " coin stake.")));
        }
    }

    private String stakeText(ProposalDraft d, DuelSide side) {
        return DuelChat.stake(d.coins(side), d.items(side).stream().map(i -> context.gui().describe(i.copy())).toList());
    }

    static List<Component> lore(String... lines) {
        List<Component> out = new ArrayList<>();
        for (String line : lines) out.add(Component.text(line, DuelChat.MUTED));
        return out;
    }

    static int indexOf(int[] slots, int slot) {
        for (int i = 0; i < slots.length; i++) if (slots[i] == slot) return i;
        return -1;
    }
}
