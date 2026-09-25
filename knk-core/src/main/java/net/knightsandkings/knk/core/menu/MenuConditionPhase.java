package net.knightsandkings.knk.core.menu;

/**
 * InventoryMenu Phase 9 (E5): when a {@code ConditionBinding} is evaluated.
 * Mirrors knk-web-api's {@code MenuConditionPhase}.
 * <ul>
 *   <li>{@link #CLICK} (default, Phase 6 behaviour): gates the click; a denial
 *       may message the player.</li>
 *   <li>{@link #RENDER}: evaluated on every render pass (per row on a row
 *       template). Item-level: a denial hides the item. Action-level: a denial
 *       drops that action from the rendered item. Re-checked silently at
 *       click time - see {@link MenuConditionEvaluator}.</li>
 * </ul>
 */
public enum MenuConditionPhase {
    CLICK,
    RENDER;

    /** Parses the persisted/DTO string ("Click"/"Render", case-insensitive); null or blank means {@link #CLICK}. */
    public static MenuConditionPhase parse(String raw, String context) {
        if (raw == null || raw.isBlank()) {
            return CLICK;
        }
        return MenuEnumParsing.parse(MenuConditionPhase.class, raw, CLICK, "phase", context);
    }
}
