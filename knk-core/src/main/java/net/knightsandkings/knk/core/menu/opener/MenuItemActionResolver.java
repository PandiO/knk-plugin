package net.knightsandkings.knk.core.menu.opener;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Click;

/**
 * Situation + click → action for the menu item (siege shop DESIGN § 5.7, § 7.1.5, P26; PandiO/knk-workspace#871),
 * Bukkit-free. The player's situation is the first registered one that applies, most specific first
 * ({@link MenuItemSituations}); its override's action for the click runs, and a click the override leaves out
 * (or no situation, or a situation without an override) uses the default table. A click the default table leaves
 * out does nothing. Runs on every click: it reads only the settings in memory and the situation checks.
 */
public final class MenuItemActionResolver {

    private final MenuItemSituations situations;

    public MenuItemActionResolver(MenuItemSituations situations) {
        this.situations = Objects.requireNonNull(situations, "situations");
    }

    /** The action for {@code click} by {@code playerId} under {@code settings}; never null. */
    public MenuItemAction resolve(MenuOpenerSettings settings, UUID playerId, Click click) {
        if (settings == null || click == null) {
            return MenuItemAction.none();
        }
        if (!settings.overrides().isEmpty()) {
            Optional<String> situation = situations.situationOf(playerId);
            if (situation.isPresent()) {
                Map<Click, MenuItemAction> override = settings.overrides().get(situation.get());
                MenuItemAction action = override == null ? null : override.get(click);
                if (action != null) {
                    return action;
                }
            }
        }
        return resolveDefault(settings, click);
    }

    /** The default table's action for {@code click}; never null. */
    public static MenuItemAction resolveDefault(MenuOpenerSettings settings, Click click) {
        MenuItemAction action = settings == null || click == null ? null : settings.clicks().get(click);
        return action != null ? action : MenuItemAction.none();
    }
}
