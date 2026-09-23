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

    /**
     * Case-insensitive on purpose: the only current caller is a human typing a
     * section name into a chat command ({@code MenuDebugCommand}'s
     * {@code /knk menu page next|prev <sectionName>}), not code matching
     * against a stored identifier - a section's persisted {@code Name} is
     * still an exact, case-preserving value everywhere else.
     */
    public Optional<RuntimeMenuSection> findSection(String name) {
        return sections.stream().filter(section -> section.name().equalsIgnoreCase(name)).findFirst();
    }
}
