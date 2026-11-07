package net.knightsandkings.knk.paper.siege.survival.kits;

import net.knightsandkings.knk.core.survival.kits.KitSelection;
import net.knightsandkings.knk.core.survival.kits.KitSelection.KitOption;
import net.knightsandkings.knk.core.survival.kits.KitSelection.PickResult;
import net.knightsandkings.knk.paper.siege.SiegeMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The kit picker of kits mode (DESIGN §8, S57; §11 inventory-menu conventions): a plain inventory menu like the Siege
 * enchant chooser ({@code SiegeEnchantMenu}), opened when a member is readied at the hub and from {@code /siege kit}; one
 * {@link KitSelection} per lobby until the match starts and locks it (S36). The Siege spawn picker is an API-seeded menu
 * (S9 authoring); this picker needs no seed so a kits-mode match is playable now (flag S7b).
 */
public final class SurvivalKitPicker {

    /** The open menu's holder (the click listener recognizes it). */
    public static final class Holder implements InventoryHolder {
        private final int lobbyId;
        private final Map<Integer, Integer> kitBySlot = new LinkedHashMap<>();
        private Inventory inventory;

        Holder(int lobbyId) {
            this.lobbyId = lobbyId;
        }

        public int lobbyId() {
            return lobbyId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Map<Integer, KitSelection> byLobby = new LinkedHashMap<>();

    /** Starts (or replaces) the selection of a lobby from the frozen equipment settings. */
    public KitSelection begin(int lobbyId, KitSelection selection) {
        byLobby.put(lobbyId, selection);
        return selection;
    }

    public Optional<KitSelection> selection(int lobbyId) {
        return Optional.ofNullable(byLobby.get(lobbyId));
    }

    public void lock(int lobbyId) {
        selection(lobbyId).ifPresent(KitSelection::lock);
    }

    public void forget(int lobbyId) {
        byLobby.remove(lobbyId);
    }

    /** Opens the picker; returns a message instead when there is nothing to pick or the match already started. */
    public Component open(Player player, int lobbyId) {
        KitSelection selection = byLobby.get(lobbyId);
        if (selection == null || selection.options().isEmpty()) return SiegeMessages.bad("This match has no kits to pick.");
        if (selection.locked()) return SiegeMessages.bad("The match started: your kit is final.");
        Holder holder = new Holder(lobbyId);
        int size = Math.min(54, ((selection.options().size() + 8) / 9) * 9);
        Inventory gui = Bukkit.createInventory(holder, size, Component.text("Pick your kit", NamedTextColor.DARK_GREEN));
        holder.inventory = gui;
        Optional<Integer> picked = selection.picked(player.getUniqueId());
        int slot = 0;
        for (KitOption option : selection.options()) {
            if (slot >= size) break;
            boolean chosen = picked.map(id -> id == option.kitId()).orElse(false);
            boolean isDefault = selection.defaultKitId().isPresent() && selection.defaultKitId().getAsInt() == option.kitId();
            gui.setItem(slot, icon(option, chosen, isDefault && picked.isEmpty()));
            holder.kitBySlot.put(slot, option.kitId());
            slot++;
        }
        player.openInventory(gui);
        return null;
    }

    /** A click in the picker: pick the kit, close, confirm. */
    public void click(Player player, Holder holder, int slot) {
        Integer kitId = holder.kitBySlot.get(slot);
        if (kitId == null) return;
        player.closeInventory();
        player.sendMessage(pick(player.getUniqueId(), holder.lobbyId(), kitId));
    }

    /** Picks by id (menu) and tells the player; shared with the command path. */
    public Component pick(UUID playerId, int lobbyId, int kitId) {
        KitSelection selection = byLobby.get(lobbyId);
        if (selection == null) return SiegeMessages.bad("This match has no kits to pick.");
        PickResult result = selection.pick(playerId, kitId);
        return switch (result) {
            case PICKED -> SiegeMessages.good("Kit picked: " + selection.option(kitId).map(KitOption::name).orElse("#" + kitId) + ". It is granted when the match starts.");
            case UNKNOWN_KIT -> SiegeMessages.bad("That kit is not one of this scenario's kits.");
            case LOCKED -> SiegeMessages.bad("The match started: your kit is final.");
        };
    }

    /** Picks by name (the {@code /siege kit <name>} fallback). */
    public Component pickByName(UUID playerId, int lobbyId, String name) {
        KitSelection selection = byLobby.get(lobbyId);
        if (selection == null) return SiegeMessages.bad("This match has no kits to pick.");
        Optional<KitOption> option = selection.optionByName(name);
        if (option.isEmpty()) {
            return SiegeMessages.bad("No kit named '" + name + "'. Kits: " + String.join(", ", names(selection)) + ".");
        }
        return pick(playerId, lobbyId, option.get().kitId());
    }

    public static List<String> names(KitSelection selection) {
        List<String> out = new ArrayList<>();
        for (KitOption o : selection.options()) out.add(o.name());
        return out;
    }

    private static ItemStack icon(KitOption option, boolean chosen, boolean defaulted) {
        ItemStack stack = new ItemStack(Material.CHEST);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(option.name(), chosen ? NamedTextColor.GREEN : NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(chosen ? "Your pick." : (defaulted ? "The default kit (yours unless you pick another)." : "Click to pick this kit."),
            NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Granted when the match starts; kept on death; removed after the match.", NamedTextColor.DARK_GRAY)
            .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        if (chosen || defaulted) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        stack.setItemMeta(meta);
        return stack;
    }
}
