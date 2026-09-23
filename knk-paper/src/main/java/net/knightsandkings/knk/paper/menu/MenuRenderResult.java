package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * The computed "desired state" of one render pass: which {@link ItemStack}
 * goes in which absolute slot, and which {@link RuntimeMenuItem} (if any)
 * backs that slot for click routing. Deliberately separate from actually
 * touching a Bukkit {@code Inventory} - computing this is the "expensive
 * work" ARCHITECTURE_DESIGN.md §6.3 says belongs off the main thread;
 * applying it is the cheap part that must happen back on the main thread.
 * <p>
 * {@code sectionsBySlot} (IMPLEMENTATION_PLAN.md Phase 7) is the other half
 * of {@code itemsBySlot}'s click-routing snapshot: a section-scoped action
 * (pagination next/prev, search/filter trigger) needs to know which
 * {@link RuntimeMenuSection} the clicked item lives in, not just which item
 * was clicked - see {@link MenuActionContext}'s javadoc for the full
 * rationale.
 * <p>
 * {@code controlHintLoreBySlot} (post-Phase-8 QOL follow-up) is a third,
 * independent slot-keyed map: the "what does this button do" lore lines
 * {@code MenuRenderer.resolveControlHints} precomputes for every function
 * button, shown only while the viewing player holds shift. Captured here
 * (and copied onto {@link OpenMenuContext} alongside the other two maps)
 * so {@link MenuControlHintListener} can toggle them live off a
 * {@code PlayerToggleSneakEvent} without a full re-render - see that
 * class's own javadoc for why a sneak toggle, not literal hover+shift, is
 * what's actually observable here.
 */
public record MenuRenderResult(
        Map<Integer, ItemStack> itemStacksBySlot,
        Map<Integer, RuntimeMenuItem> itemsBySlot,
        Map<Integer, RuntimeMenuSection> sectionsBySlot,
        Map<Integer, List<String>> controlHintLoreBySlot
) {
}
