package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.MenuBlink;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * KNG-125 (FR-2.6.1, the v1/v2 {@code MenuItemBlink}): makes {@code BLINK} items blink. Each slot
 * in {@link OpenMenuContext#blinkTicksBySlot()} alternates between its own {@code ItemStack} and a
 * {@link #FLASH_MATERIAL yellow glass pane} carrying the same name and lore, every interval
 * ({@link MenuBlink}).
 * <p>
 * Unlike v1/v2 (an async timer per item, mutating the Inventory off the main thread, never stopped
 * on close) this runs from {@link MenuAutoRefreshTask} on the main thread, once per tick, over the
 * open menus only:
 * <ul>
 *   <li>only the {@code ItemStack} in the live Inventory is swapped, the way
 *   {@link MenuControlHintListener} adds its lore - no re-render, and the click-routing snapshot
 *   ({@link OpenMenuContext#itemsBySlot()}) is untouched, so a click on the pane runs the item's
 *   own actions;</li>
 *   <li>a closed menu has no {@link OpenMenuContext}, so it is not visited (it stops on close);</li>
 *   <li>a re-render replaces the blinking slots with its own: a slot that now holds another,
 *   non-blinking item is never touched again; one that still blinks keeps its rhythm, because the
 *   phase is counted from the tick the Inventory opened;</li>
 *   <li>a slot whose item was replaced by something else meanwhile is left alone (v2's guard).</li>
 * </ul>
 */
public final class MenuItemBlinker {

    /** v1 data-4 {@code STAINED_GLASS_PANE}, v2 {@code YELLOW_STAINED_GLASS_PANE}. */
    public static final Material FLASH_MATERIAL = Material.YELLOW_STAINED_GLASS_PANE;

    private final UnaryOperator<ItemStack> flashFactory;

    public MenuItemBlinker() {
        this(MenuItemBlinker::flashOf);
    }

    /** @param flashFactory builds the pane shown for a slot from the item it replaces (tests) */
    MenuItemBlinker(UnaryOperator<ItemStack> flashFactory) {
        this.flashFactory = flashFactory;
    }

    /**
     * Main thread. Puts every blinking slot of {@code context} in the phase it should show at
     * {@code nowTick}. {@code revealControls}: whether the player is sneaking - the restored item
     * gets its control hints then, as {@code MenuRenderer.applyToInventory} would give it.
     */
    public void tick(OpenMenuContext context, long nowTick, boolean revealControls) {
        Map<Integer, Integer> blinking = context.blinkTicksBySlot();
        Set<Integer> flashed = context.flashedSlots();
        if (blinking.isEmpty()) {
            flashed.clear();
            return;
        }
        Inventory inventory = context.inventory();
        MenuRenderResult render = context.lastRender();
        for (Map.Entry<Integer, Integer> entry : blinking.entrySet()) {
            int slot = entry.getKey();
            boolean wantFlash = MenuBlink.flashShown(context.blinkStartTick(), nowTick, entry.getValue());
            if (wantFlash == flashed.contains(slot)) {
                continue;
            }
            ItemStack current = inventory.getItem(slot);
            if (wantFlash) {
                ItemStack original = render.itemStacksBySlot().get(slot);
                if (current == null || original == null || current.getType() != original.getType()) {
                    continue;
                }
                inventory.setItem(slot, flashFactory.apply(current));
                flashed.add(slot);
            } else {
                flashed.remove(slot);
                if (current == null || current.getType() != FLASH_MATERIAL) {
                    continue;
                }
                inventory.setItem(slot, MenuRenderer.stackFor(render, slot, revealControls));
            }
        }
        flashed.retainAll(blinking.keySet());
    }

    /** The pane shown for {@code original}: its name (the item's own name when it has none set) and lore. */
    static ItemStack flashOf(ItemStack original) {
        ItemStack flash = new ItemStack(FLASH_MATERIAL, Math.max(1, original.getAmount()));
        ItemMeta meta = flash.getItemMeta();
        ItemMeta from = original.getItemMeta();
        if (meta == null) {
            return flash;
        }
        if (from != null && from.hasDisplayName()) {
            meta.displayName(from.displayName());
        } else {
            meta.displayName(original.effectiveName());
        }
        if (from != null && from.hasLore()) {
            meta.lore(from.lore());
        }
        flash.setItemMeta(meta);
        return flash;
    }
}
