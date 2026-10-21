package net.knightsandkings.knk.paper.duel.ui;

import net.knightsandkings.knk.core.duel.DuelNegotiation;
import net.knightsandkings.knk.core.duel.DuelOffer;
import net.knightsandkings.knk.core.duel.DuelSide;
import net.knightsandkings.knk.core.duel.GearMode;
import net.knightsandkings.knk.core.duel.StakeContract;
import net.knightsandkings.knk.core.duel.StakeItem;
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
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * The read-only offer view (arena duels DESIGN §6.4): the editor's layout without its buttons, plus
 * {@code [Accept] [Counter] [Decline]} for the receiver. The last allowed offer has no Counter (D2). The author (or a
 * receiver after the turn moved on) sees the contract only. Every click is cancelled by {@code DuelMenuListener}.
 */
public final class DuelOfferView {

    public static final int SIZE = 54;
    public static final int DECLINE = 45;
    public static final int COUNTER = 49;
    public static final int ACCEPT = 53;

    /** The answers; the controller runs them as the matching {@code /duel} command would. */
    public interface Actions {
        void accept(Player player, long offerId);

        void counter(Player player, long offerId);

        void decline(Player player, long offerId);
    }

    /** The open view: which offer it shows. */
    public static final class Holder implements InventoryHolder {
        private final long offerId;
        private final boolean canAnswer;
        private final boolean canCounter;
        private Inventory inventory;

        Holder(long offerId, boolean canAnswer, boolean canCounter) {
            this.offerId = offerId;
            this.canAnswer = canAnswer;
            this.canCounter = canCounter;
        }

        public long offerId() {
            return offerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final DuelGui gui;
    private final IntFunction<String> kitName;
    private final Function<String, Optional<ItemStack>> copies;
    private final Function<UUID, String> names;
    private final Actions actions;

    /**
     * @param copies the icon copy of a referenced stack ({@code DuelStakeItems.get(refId)})
     * @param names  a player's name
     */
    public DuelOfferView(DuelGui gui, IntFunction<String> kitName, Function<String, Optional<ItemStack>> copies,
                         Function<UUID, String> names, Actions actions) {
        this.gui = Objects.requireNonNull(gui, "gui");
        this.kitName = Objects.requireNonNull(kitName, "kitName");
        this.copies = Objects.requireNonNull(copies, "copies");
        this.names = Objects.requireNonNull(names, "names");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    /** Opens the current offer of {@code negotiation} for {@code viewer} (one of its two players). */
    public Holder open(Player viewer, DuelNegotiation negotiation, String arenaName, int secondsLeft) {
        DuelOffer offer = Objects.requireNonNull(negotiation.currentOffer(), "offer");
        DuelSide viewerSide = Objects.requireNonNull(negotiation.sideOf(viewer.getUniqueId()), "viewer side");
        boolean canAnswer = viewerSide == offer.receiver() && negotiation.state() == DuelNegotiation.State.AWAITING_RESPONSE;
        Holder holder = new Holder(offer.offerId(), canAnswer, canAnswer && offer.canBeCountered());
        String author = names.apply(negotiation.playerOf(offer.author()));
        holder.inventory = gui.create(holder, SIZE, Component.text("Duel offer " + offer.number() + "/"
                + negotiation.settings().maxOffers() + " from " + author, NamedTextColor.DARK_RED));
        render(holder, negotiation, offer, viewerSide, arenaName, secondsLeft);
        viewer.openInventory(holder.inventory);
        return holder;
    }

    private void render(Holder holder, DuelNegotiation negotiation, DuelOffer offer, DuelSide viewerSide,
                        String arenaName, int secondsLeft) {
        Inventory inv = holder.inventory;
        StakeContract contract = offer.contract();
        UUID me = negotiation.playerOf(viewerSide);
        UUID other = negotiation.playerOf(viewerSide.other());
        inv.setItem(DuelProposalEditor.YOU, gui.head(me, Component.text("You", DuelChat.ACCENT),
                DuelProposalEditor.lore("Your stake: " + stakeText(contract, viewerSide))));
        inv.setItem(DuelProposalEditor.THEM, gui.head(other, Component.text(names.apply(other), DuelChat.ACCENT),
                DuelProposalEditor.lore("Their stake: " + stakeText(contract, viewerSide.other()))));
        GearMode mode = contract.gearMode();
        boolean drop = mode == GearMode.OWN_GEAR_DROP;
        List<Component> gearLore = new ArrayList<>();
        for (String line : DuelChat.gearModeLore(mode)) gearLore.add(Component.text(line, drop ? DuelChat.BAD : NamedTextColor.WHITE));
        inv.setItem(DuelProposalEditor.GEAR, gui.icon(drop ? Material.TNT : Material.DIAMOND_CHESTPLATE,
                Component.text("Gear: " + DuelChat.gearModeName(mode), drop ? DuelChat.BAD : DuelChat.ACCENT,
                        drop ? TextDecoration.BOLD : TextDecoration.ITALIC), gearLore));
        if (mode == GearMode.FIXED_KIT && contract.fixedKitId() != null) {
            inv.setItem(DuelProposalEditor.KIT, gui.icon(Material.IRON_CHESTPLATE,
                    Component.text("Kit: " + kitName.apply(contract.fixedKitId()), NamedTextColor.AQUA), List.of()));
        }
        inv.setItem(DuelProposalEditor.ARENA, gui.icon(Material.IRON_SWORD, Component.text(arenaName, DuelChat.ACCENT),
                DuelProposalEditor.lore("Offer " + offer.number() + " of " + negotiation.settings().maxOffers()
                        + (offer.last() ? " (the last)" : ""), secondsLeft + " s left to answer.")));
        long fee = negotiation.settings().feeFor(contract.potCoins());
        inv.setItem(DuelProposalEditor.FEE, gui.icon(Material.GOLD_NUGGET, Component.text("Arena fee: "
                + negotiation.settings().feePercent() + "%", DuelChat.ACCENT),
                DuelProposalEditor.lore(DuelChat.coins(fee) + " coins on a decisive result.",
                        "No fee on draws, cancels or friendly duels.")));
        for (int slot : DuelProposalEditor.DIVIDER) {
            inv.setItem(slot, gui.icon(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of()));
        }
        items(inv, contract.stakeOf(viewerSide).items(), DuelProposalEditor.LEFT_ITEMS);
        items(inv, contract.stakeOf(viewerSide.other()).items(), DuelProposalEditor.RIGHT_ITEMS);
        inv.setItem(DuelProposalEditor.YOUR_AMOUNT, gui.icon(Material.GOLD_INGOT, Component.text("Your coins: "
                + DuelChat.coins(contract.stakeOf(viewerSide).coins()), DuelChat.ACCENT), List.of()));
        inv.setItem(DuelProposalEditor.THEIR_AMOUNT, gui.icon(Material.GOLD_INGOT, Component.text(names.apply(other)
                + "'s coins: " + DuelChat.coins(contract.stakeOf(viewerSide.other()).coins()), DuelChat.ACCENT), List.of()));
        if (holder.canAnswer) {
            inv.setItem(DECLINE, gui.icon(Material.RED_CONCRETE, Component.text("Decline", DuelChat.BAD),
                    DuelProposalEditor.lore("Turn the duel down.")));
            if (holder.canCounter) {
                inv.setItem(COUNTER, gui.icon(Material.ORANGE_CONCRETE, Component.text("Counter", DuelChat.ACCENT),
                        DuelProposalEditor.lore("Answer with your own offer.")));
            }
            List<Component> acceptLore = new ArrayList<>(DuelProposalEditor.lore("Your stake is taken now and the",
                    "duel starts with a teleport to the arena."));
            if (drop) acceptLore.add(Component.text("THE LOSER'S INVENTORY GOES TO THE WINNER.", DuelChat.BAD, TextDecoration.BOLD));
            inv.setItem(ACCEPT, gui.icon(Material.LIME_CONCRETE, Component.text("Accept", DuelChat.GOOD), acceptLore));
        }
    }

    /** A click in the view; the listener has already cancelled it. */
    public void click(Player player, Holder holder, int slot) {
        if (!holder.canAnswer) return;
        if (slot == ACCEPT) {
            player.closeInventory();
            actions.accept(player, holder.offerId);
        } else if (slot == DECLINE) {
            player.closeInventory();
            actions.decline(player, holder.offerId);
        } else if (slot == COUNTER && holder.canCounter) {
            actions.counter(player, holder.offerId);
        }
    }

    private void items(Inventory inv, List<StakeItem> stake, int[] slots) {
        for (int i = 0; i < stake.size() && i < slots.length; i++) {
            inv.setItem(slots[i], gui.stakeIcon(copies.apply(stake.get(i).refId()).orElse(null),
                    List.of(Component.text("Staked in this offer", DuelChat.ACCENT))));
        }
    }

    private String stakeText(StakeContract contract, DuelSide side) {
        return DuelChat.stake(contract.stakeOf(side).coins(), contract.stakeOf(side).items().stream()
                .map(item -> copies.apply(item.refId()).map(gui::describe)
                        .orElse(DuelGui.materialName(item.material()) + (item.amount() > 1 ? " x" + item.amount() : "")))
                .toList());
    }
}
