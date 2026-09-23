package net.knightsandkings.knk.paper.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Post-Phase-8 QOL follow-up: approximates "hold shift to reveal a
 * tooltip's extra lines" - the developer's ask, and a real client-side
 * behavior Bukkit only exposes natively for bundles, never for an
 * arbitrary item (there is no server-visible "which slot is the mouse
 * currently hovering" signal to react to). Reacting to
 * {@link PlayerToggleSneakEvent} instead is the closest available
 * approximation: the instant a player with a KnK menu open starts or stops
 * sneaking, every function button's precomputed control-hint lore
 * ({@link OpenMenuContext#controlHintLoreBySlot()}, captured at the last
 * render by {@code MenuRenderer.resolveControlHints}) is appended to or
 * stripped from the live Inventory {@link ItemStack}s directly - no
 * re-render, no data re-fetch, just a lore-only mutation of what's already
 * on screen. Minecraft redraws a hovered slot's tooltip from its current
 * ItemStack every client tick, so the player sees this as instant even
 * without moving their mouse off the slot and back.
 * <p>
 * Deliberately narrow scope: this only ever adds/removes the exact
 * precomputed hint lines (matched by comparing the lore's trailing entries
 * against that list) - it never touches the item's other lore lines (page
 * count, active filter/search value, etc.), which stay exactly as the last
 * real render left them.
 */
public final class MenuControlHintListener implements Listener {

    private final OpenMenuContextRegistry openMenuContextRegistry;

    public MenuControlHintListener(OpenMenuContextRegistry openMenuContextRegistry) {
        this.openMenuContextRegistry = openMenuContextRegistry;
    }

    @EventHandler
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        Optional<OpenMenuContext> context = openMenuContextRegistry.get(player.getUniqueId());
        if (context.isEmpty()) {
            return;
        }

        Inventory inventory = context.get().inventory();
        boolean sneaking = event.isSneaking();

        for (Map.Entry<Integer, List<String>> entry : context.get().controlHintLoreBySlot().entrySet()) {
            List<String> hints = entry.getValue();
            if (hints.isEmpty()) {
                continue;
            }
            toggleHints(inventory, entry.getKey(), hints, sneaking);
        }
    }

    private static void toggleHints(Inventory inventory, int slot, List<String> hints, boolean sneaking) {
        ItemStack itemStack = inventory.getItem(slot);
        if (itemStack == null) {
            return;
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return;
        }

        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        boolean hintsAlreadyShown = lore.size() >= hints.size()
                && lore.subList(lore.size() - hints.size(), lore.size()).equals(hints);

        if (sneaking == hintsAlreadyShown) {
            return;
        }

        if (sneaking) {
            lore.addAll(hints);
        } else {
            lore = new ArrayList<>(lore.subList(0, lore.size() - hints.size()));
        }

        meta.setLore(lore);
        itemStack.setItemMeta(meta);
        inventory.setItem(slot, itemStack);
    }
}
