package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.core.menu.MenuContextParams;
import net.knightsandkings.knk.core.menu.RuntimeMenuItem;
import net.knightsandkings.knk.core.menu.RuntimeMenuSection;
import net.knightsandkings.knk.core.menu.SectionView;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * The computed "desired state" of one render pass: which {@link ItemStack}
 * goes in which absolute slot, and which {@link RuntimeMenuItem} (if any)
 * backs that slot for click routing. Deliberately separate from actually
 * touching a Bukkit {@code Inventory} - applying it is the cheap part that
 * must happen on the main thread.
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
 * shown only while the viewing player holds shift - see
 * {@link MenuControlHintListener}.
 * <p>
 * InventoryMenu Phase 9 additions: {@code itemsBySlot} now holds the
 * <em>rendered snapshot</em> of each item (effective display mode, actions
 * that survived their Render conditions - E5/E6); {@code rowsBySlot} (E3) the
 * row a row-template slot was rendered for; {@code sectionViewsBySectionId}
 * (E9) the {@code $section$} paging facts per section, reused at click time;
 * {@code materialNamespaceKeys} the material-ref lookups this pass needed,
 * kept so an auto-refresh (E4) never has to repeat them; {@code menuContext}
 * (E1) the ctx params the pass rendered with.
 */
public record MenuRenderResult(
        Map<Integer, ItemStack> itemStacksBySlot,
        Map<Integer, RuntimeMenuItem> itemsBySlot,
        Map<Integer, RuntimeMenuSection> sectionsBySlot,
        Map<Integer, List<String>> controlHintLoreBySlot,
        Map<Integer, Object> rowsBySlot,
        Map<Integer, SectionView> sectionViewsBySectionId,
        Map<Integer, String> materialNamespaceKeys,
        MenuContextParams menuContext
) {
}
