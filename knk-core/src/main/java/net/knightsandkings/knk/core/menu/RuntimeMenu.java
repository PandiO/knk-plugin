package net.knightsandkings.knk.core.menu;

import java.util.List;
import java.util.Optional;

/**
 * The rendered-instance side of {@code KnkMenuTemplate} (FR-2.1.1) - the root
 * of the assembled Menu -&gt; MenuSection -&gt; MenuItem composite tree
 * (composite pattern per ARCHITECTURE_DESIGN.md §2.1), produced by
 * {@link MenuTemplateAssembler} from a persisted {@code KnkMenuTemplate}.
 */
public record RuntimeMenu(
        String key,
        String title,
        int height,
        MenuGrowth growth,
        Integer backgroundMaterialRefId,
        List<RuntimeMenuSection> sections
) {

    /** Total inventory slots for this menu ({@code height} rows of 9). */
    public int totalSlots() {
        return height * MenuSlotCalculator.MENU_WIDTH;
    }

    public Optional<RuntimeMenuSection> findSection(String name) {
        return sections.stream().filter(section -> section.name().equals(name)).findFirst();
    }
}
