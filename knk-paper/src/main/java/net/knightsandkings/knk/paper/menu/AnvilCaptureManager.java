package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.MenuTextPrompt;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * In-house free-text capture for the menus' search and filter prompts (IMPLEMENTATION_PLAN.md
 * Phase 7): an anvil the player types into the normal Minecraft way.
 * <p>
 * PandiO/knk-workspace#128 (Paper 26.3): the prompt used to open {@code Bukkit.createInventory(null,
 * InventoryType.ANVIL, ...)}. Paper builds that as a custom inventory (its
 * {@code CraftInventoryCreator} maps ANVIL to the custom-inventory converter), not an
 * {@code AnvilInventory}, so the typed text was always read as "" (Confirm cleared the search), the
 * prepare/click handlers matched by inventory equality never recognised the anvil (vanilla's own
 * rename preview and "Enchantment Cost: 1" stayed), and both papers said "Click to cancel". Now:
 * <ul>
 *   <li>the anvil is a real anvil menu from {@link MenuType#ANVIL}, and the session holds its
 *       {@link AnvilView}; events are matched by that view, the typed text is
 *       {@link AnvilView#getRenameText()} and the cost is set to 0 through the view;</li>
 *   <li>the labels come from {@link MenuTextPrompt}: the left (input) item is a barrier with
 *       "Cancel / Back to the menu", named after the current search or filter value because the
 *       client fills the rename field with the input item's name; the right (result) item is a
 *       green {@code Search for "<text>"}, or {@code Clear search} when the field is blank;</li>
 *   <li>Cancel (the left item, or Escape) runs {@code onCancel}, which reopens the menu it came
 *       from (see {@code MenuService#promptSearch}); any other close (another inventory opened,
 *       a disconnect) ends the prompt without a callback.</li>
 * </ul>
 * Slot 1 (the second ingredient) stays empty: with an item there vanilla validates a repair or
 * combine instead of a plain rename (live finding, Phase 7/8 QOL round 2). A {@code MenuType.ANVIL}
 * view is tied to the player's position, and vanilla hands the input items back to the player when
 * it closes, so the anvil's slots are emptied before every close.
 */
public final class AnvilCaptureManager implements Listener {

    /** The input item: the rename target and the Cancel button. */
    static final int INPUT_SLOT = 0;
    /** The result item: Confirm. */
    static final int RESULT_SLOT = 2;

    /** Builds, but does not open, an anvil view for {@code player} (Paper's {@code MenuType.ANVIL} outside tests). */
    @FunctionalInterface
    interface AnvilViews {
        AnvilView create(Player player, Component title);
    }

    /** Turns a prompt button into an item (an {@code ItemStack} needs a server, so tests swap it out). */
    @FunctionalInterface
    interface ButtonItems {
        ItemStack create(MenuTextPrompt.Button button);
    }

    private final Plugin plugin;
    private final AnvilViews views;
    private final ButtonItems items;
    private final Consumer<Runnable> nextTick;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public AnvilCaptureManager(Plugin plugin) {
        this(plugin, AnvilCaptureManager::createAnvilView, AnvilCaptureManager::buttonItem,
                task -> Bukkit.getScheduler().runTask(plugin, task));
    }

    AnvilCaptureManager(Plugin plugin, AnvilViews views, ButtonItems items, Consumer<Runnable> nextTick) {
        this.plugin = plugin;
        this.views = views;
        this.items = items;
        this.nextTick = nextTick;
    }

    /**
     * Opens the anvil prompt (main thread). Opening it closes the menu the player had open.
     *
     * @param onComplete receives the typed text, without colour codes and trimmed; "" when the
     *                   field was blank (search/filter treat that as "clear")
     * @param onCancel   runs when the player clicks the left item or presses Escape
     */
    public void startTextCapture(Player player, MenuTextPrompt prompt, Consumer<String> onComplete, Runnable onCancel) {
        Session replaced = sessions.remove(player.getUniqueId());
        if (replaced != null) {
            replaced.finished = true;
            emptySlots(replaced.view);
        }

        player.sendMessage(Component.text(prompt.chatLine(), NamedTextColor.YELLOW));
        AnvilView view = views.create(player, Component.text(prompt.title()));
        Session session = new Session(view, prompt, onComplete, onCancel);
        sessions.put(player.getUniqueId(), session);
        view.setRepairCost(0);
        view.getTopInventory().setItem(INPUT_SLOT, items.create(prompt.cancelButton()));
        player.openInventory(view);
    }

    /** Whether {@code playerId} has a prompt open. */
    boolean isCapturing(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    /** Replaces vanilla's rename preview with the Confirm item for the typed text, at cost 0. */
    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilView view = event.getView();
        Session session = sessionFor(view.getPlayer(), view);
        if (session == null || session.finished) {
            return;
        }
        view.setRepairCost(0);
        view.setRepairItemCountCost(0);
        event.setResult(items.create(session.prompt.resultButton(view.getRenameText())));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Session session = sessionFor(player, event.getView());
        if (session == null) {
            return;
        }
        // Nothing moves in or out of the prompt; only the two buttons do something.
        event.setCancelled(true);
        if (session.finished || event.getRawSlot() < 0 || event.getRawSlot() >= session.view.getTopInventory().getSize()) {
            return;
        }
        if (event.getRawSlot() == RESULT_SLOT) {
            String text = extractText(session.view);
            finish(player, session, () -> session.onComplete.accept(text));
        } else if (event.getRawSlot() == INPUT_SLOT) {
            finish(player, session, session.onCancel);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (sessionFor(event.getWhoClicked(), event.getView()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Session session = sessionFor(event.getPlayer(), event.getView());
        if (session == null) {
            return;
        }
        sessions.remove(event.getPlayer().getUniqueId(), session);
        emptySlots(session.view);
        if (session.finished) {
            return;
        }
        session.finished = true;
        if (event.getReason() == InventoryCloseEvent.Reason.PLAYER) {
            // Escape: the same as the Cancel item.
            nextTick.accept(session.onCancel);
        }
    }

    /** A reload or shutdown: close every prompt now, so nobody is left with the anvil's items. */
    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) {
            closeAll();
        }
    }

    void closeAll() {
        for (Map.Entry<UUID, Session> entry : new ArrayList<>(sessions.entrySet())) {
            Session session = entry.getValue();
            session.finished = true;
            emptySlots(session.view);
            sessions.remove(entry.getKey(), session);
            HumanEntity player = session.view.getPlayer();
            if (player != null && isShowing(player, session.view)) {
                player.closeInventory();
            }
        }
    }

    /**
     * The click handler must not close the inventory it runs in (reentrancy), so the close and the
     * callback run next tick. The slots are emptied now, in case the player closes it first.
     */
    private void finish(Player player, Session session, Runnable callback) {
        session.finished = true;
        emptySlots(session.view);
        nextTick.accept(() -> {
            sessions.remove(player.getUniqueId(), session);
            if (isShowing(player, session.view)) {
                player.closeInventory();
            }
            callback.run();
        });
    }

    private Session sessionFor(HumanEntity player, InventoryView view) {
        if (player == null || view == null) {
            return null;
        }
        Session session = sessions.get(player.getUniqueId());
        return session != null && sameView(session.view, view) ? session : null;
    }

    private static boolean isShowing(HumanEntity player, AnvilView view) {
        return sameView(view, player.getOpenInventory());
    }

    private static boolean sameView(AnvilView ours, InventoryView other) {
        if (other == null) {
            return false;
        }
        if (ours == other) {
            return true;
        }
        Inventory top = other.getTopInventory();
        return top != null && top.equals(ours.getTopInventory());
    }

    /** The typed text: {@link AnvilView#getRenameText()}, without colour codes and trimmed. */
    static String extractText(AnvilView view) {
        return MenuTextPrompt.normalize(view.getRenameText());
    }

    private static void emptySlots(AnvilView view) {
        view.getTopInventory().clear();
    }

    private static AnvilView createAnvilView(Player player, Component title) {
        return MenuType.ANVIL.builder()
                .title(title)
                .checkReachable(false)
                .build(player);
    }

    private static ItemStack buttonItem(MenuTextPrompt.Button button) {
        Material material = button.tone() == MenuTextPrompt.Tone.CANCEL ? Material.BARRIER : Material.PAPER;
        TextColor nameColor = switch (button.tone()) {
            case CANCEL -> NamedTextColor.WHITE;
            case CONFIRM -> NamedTextColor.GREEN;
            case CLEAR -> NamedTextColor.YELLOW;
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.customName(plain(button.name(), nameColor));
            List<Component> lore = new ArrayList<>();
            for (int i = 0; i < button.lore().size(); i++) {
                boolean cancelLine = button.tone() == MenuTextPrompt.Tone.CANCEL && i == 0;
                lore.add(plain(button.lore().get(i), cancelLine ? NamedTextColor.RED : NamedTextColor.GRAY));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static Component plain(String text, TextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static final class Session {
        final AnvilView view;
        final MenuTextPrompt prompt;
        final Consumer<String> onComplete;
        final Runnable onCancel;
        /** Confirmed, cancelled or replaced: later clicks and the close itself do nothing more. */
        volatile boolean finished;

        Session(AnvilView view, MenuTextPrompt prompt, Consumer<String> onComplete, Runnable onCancel) {
            this.view = view;
            this.prompt = prompt;
            this.onComplete = onComplete;
            this.onCancel = onCancel;
        }
    }
}
