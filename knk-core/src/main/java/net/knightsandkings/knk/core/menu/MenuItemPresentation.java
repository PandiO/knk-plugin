package net.knightsandkings.knk.core.menu;

import net.knightsandkings.knk.core.domain.menu.KnkVariableBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * InventoryMenu Phase 9 (E6/E7/E8): everything a render pass resolves for one
 * item, Bukkit-free - knk-paper's {@code MenuItemBukkitMapper} only maps this
 * onto an {@code ItemStack}. Keeping the resolution here is what makes the
 * item-meta bindings, the colour precedence and the lore omission/expansion
 * rules unit-testable without a server.
 *
 * @param name        resolved name with E7 colours applied ({@code §} form), or
 *                    null (no Name binding, or it resolved to null - E8)
 * @param lore        resolved lore lines (E8 omission/expansion applied), each
 *                    with E7 colours applied
 * @param materialKey value of a {@code Material} binding (namespace key or enum
 *                    name), or null when not bound/blank - knk-paper resolves it
 *                    and falls back to {@code materialRefId}, then PAPER
 * @param amount      {@code Amount} binding clamped to 1-64, or null when not
 *                    bound/invalid (the {@code Amount} column is used then)
 * @param banner      parsed {@code BannerPatterns} binding, or null
 * @param skullOwner  {@code SkullOwner} binding (UUID or name), or null
 * @param displayMode effective display mode: a {@code DisplayMode} binding
 *                    overrides the column when it parses
 * @param warnings    data problems found while resolving (an unparsable amount,
 *                    an unknown display mode, banner errors) - knk-paper logs
 *                    each once
 */
public record MenuItemPresentation(
        String name,
        List<String> lore,
        String materialKey,
        Integer amount,
        BannerPatternSpec banner,
        String skullOwner,
        MenuDisplayMode displayMode,
        List<String> warnings
) {

    public static final String MATERIAL = "Material";
    public static final String AMOUNT = "Amount";
    public static final String BANNER_PATTERNS = "BannerPatterns";
    public static final String SKULL_OWNER = "SkullOwner";
    public static final String DISPLAY_MODE = "DisplayMode";
    public static final int MIN_AMOUNT = 1;
    public static final int MAX_AMOUNT = 64;

    public MenuItemPresentation {
        lore = List.copyOf(lore);
        warnings = List.copyOf(warnings);
    }

    /**
     * Resolves {@code item}'s bindings against {@code scope}. {@code rowScope}
     * is set when the item is a row template being rendered for one row.
     */
    public static MenuItemPresentation resolve(RuntimeMenuItem item, MenuSession session, Map<String, Object> scope,
                                               long currentTick, VariableResolver.RowScope rowScope) {
        List<KnkVariableBinding> bindings = item.variableBindings();
        List<String> warnings = new ArrayList<>();

        String rawName = VariableResolver.resolveName(bindings, session, scope, currentTick, rowScope);
        String name = MenuTextColors.apply(item.chatColorName(), rawName);

        List<String> lore = new ArrayList<>();
        for (String line : VariableResolver.resolveLore(bindings, session, scope, currentTick, rowScope)) {
            lore.add(MenuTextColors.apply(item.chatColorDescription(), line));
        }

        String materialKey = blankToNull(property(bindings, MATERIAL, session, scope, currentTick, rowScope));

        Integer amount = null;
        String rawAmount = blankToNull(property(bindings, AMOUNT, session, scope, currentTick, rowScope));
        if (rawAmount != null) {
            try {
                amount = Math.max(MIN_AMOUNT, Math.min(MAX_AMOUNT, Integer.parseInt(rawAmount.trim())));
            } catch (NumberFormatException e) {
                warnings.add("Amount binding value '" + rawAmount + "' is not an integer - using the Amount column");
            }
        }

        BannerPatternSpec banner = BannerPatternSpec.parse(property(bindings, BANNER_PATTERNS, session, scope, currentTick, rowScope));
        if (banner != null) {
            banner.errors().forEach(error -> warnings.add("BannerPatterns: " + error));
        }

        String skullOwner = blankToNull(property(bindings, SKULL_OWNER, session, scope, currentTick, rowScope));

        MenuDisplayMode displayMode = item.displayMode();
        String rawDisplayMode = blankToNull(property(bindings, DISPLAY_MODE, session, scope, currentTick, rowScope));
        if (rawDisplayMode != null) {
            try {
                displayMode = MenuDisplayMode.valueOf(rawDisplayMode.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                warnings.add("DisplayMode binding value '" + rawDisplayMode + "' is not NORMAL/DISABLED/HIGHLIGHT/HIDDEN"
                        + " - using the DisplayMode column");
            }
        }

        return new MenuItemPresentation(name, lore, materialKey, amount, banner, skullOwner, displayMode, warnings);
    }

    private static String property(List<KnkVariableBinding> bindings, String targetProperty, MenuSession session,
                                   Map<String, Object> scope, long currentTick, VariableResolver.RowScope rowScope) {
        return VariableResolver.resolveByTargetProperty(bindings, targetProperty, session, scope, currentTick, rowScope)
                .orElse(null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
