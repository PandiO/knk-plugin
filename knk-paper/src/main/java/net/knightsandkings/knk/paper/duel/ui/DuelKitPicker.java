package net.knightsandkings.knk.paper.duel.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * The kit picker (arena duels DESIGN §6.4, §8.4): the arena's eligible kits with a preview of their items.
 * <ul>
 *   <li>For a {@code FIXED_KIT} proposal it picks the kit of the draft and goes back to the editor.</li>
 *   <li>In the warmup of a {@code KIT_CHOICE} match it picks (or swaps) the duelist's own kit, and shows the
 *       opponent's pick.</li>
 * </ul>
 * Every click is cancelled by {@code DuelMenuListener}.
 */
public final class DuelKitPicker {

    public static final int SIZE = 27;
    public static final int INFO = 4;
    public static final int FIRST_KIT = 9;
    public static final int MAX_KITS = 9;
    public static final int BACK = 22;

    public interface Actions {
        /** A kit for the draft's {@code FIXED_KIT} mode. */
        void pickedForDraft(Player player, ProposalDraft draft, int kitId);

        /** {@code KIT_CHOICE} in the warmup: the player's own kit. */
        void pickedInWarmup(Player player, int kitId);

        /** Back to the editor without changing the kit. */
        void back(Player player, ProposalDraft draft);
    }

    /** The open picker. {@code draft} is set for the editor's picker, null in the warmup. */
    public static final class Holder implements InventoryHolder {
        private final ProposalDraft draft;
        private final Map<Integer, Integer> kitBySlot = new HashMap<>();
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

    private final DuelGui gui;
    private final IntFunction<String> kitName;
    private final IntFunction<List<String>> preview;
    private final Actions actions;

    /** @param preview the kit's items, one line each (empty while they load) */
    public DuelKitPicker(DuelGui gui, IntFunction<String> kitName, IntFunction<List<String>> preview, Actions actions) {
        this.gui = Objects.requireNonNull(gui, "gui");
        this.kitName = Objects.requireNonNull(kitName, "kitName");
        this.preview = preview == null ? id -> List.of() : preview;
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    /** The editor's picker for a {@code FIXED_KIT} draft. */
    public Holder openForDraft(Player player, ProposalDraft draft) {
        Holder holder = new Holder(draft);
        holder.inventory = gui.create(holder, SIZE, Component.text("Pick the duel kit", NamedTextColor.DARK_RED));
        fill(holder, draft.settings().eligibleKitIds(), draft.fixedKitId(), "Both of you fight with this kit.");
        holder.inventory.setItem(BACK, gui.icon(Material.ARROW, Component.text("Back", NamedTextColor.WHITE),
                DuelProposalEditor.lore("Back to the offer.")));
        player.openInventory(holder.inventory);
        return holder;
    }

    /**
     * The warmup picker ({@code KIT_CHOICE}).
     *
     * @param current      the duelist's kit now (the placeholder until they pick)
     * @param opponentPick the opponent's pick, null while they haven't picked
     */
    public Holder openInWarmup(Player player, List<Integer> kitIds, Integer current, UUID opponentId,
                               String opponentName, Integer opponentPick, int secondsLeft) {
        Holder holder = new Holder(null);
        holder.inventory = gui.create(holder, SIZE, Component.text("Pick your duel kit", NamedTextColor.DARK_RED));
        fill(holder, kitIds, current, "Fight with this kit.");
        holder.inventory.setItem(INFO, gui.head(opponentId,
                Component.text(opponentName, DuelChat.ACCENT), DuelProposalEditor.lore(opponentPick == null
                        ? opponentName + " hasn't picked yet." : opponentName + " picked " + kitName.apply(opponentPick) + ".",
                        "The pick locks when the countdown starts (" + secondsLeft + " s).")));
        player.openInventory(holder.inventory);
        return holder;
    }

    private void fill(Holder holder, List<Integer> kitIds, Integer current, String hint) {
        for (int i = 0; i < kitIds.size() && i < MAX_KITS; i++) {
            int kitId = kitIds.get(i);
            boolean picked = current != null && current == kitId;
            List<Component> lore = new ArrayList<>();
            for (String line : preview.apply(kitId)) lore.add(Component.text("• " + line, NamedTextColor.WHITE));
            lore.add(Component.empty());
            lore.add(Component.text(picked ? "✔ Picked" : hint, picked ? DuelChat.GOOD : DuelChat.MUTED));
            holder.inventory.setItem(FIRST_KIT + i, gui.icon(picked ? Material.ENCHANTED_BOOK : Material.BOOK,
                    Component.text(kitName.apply(kitId), picked ? DuelChat.GOOD : NamedTextColor.AQUA), lore));
            holder.kitBySlot.put(FIRST_KIT + i, kitId);
        }
    }

    /** A click in the picker; the listener has already cancelled it. */
    public void click(Player player, Holder holder, int slot) {
        Integer kitId = holder.kitBySlot.get(slot);
        if (holder.draft != null) {
            if (kitId != null) {
                actions.pickedForDraft(player, holder.draft, kitId);
            } else if (slot == BACK) {
                actions.back(player, holder.draft);
            }
            return;
        }
        if (kitId != null) actions.pickedInWarmup(player, kitId);
    }
}
