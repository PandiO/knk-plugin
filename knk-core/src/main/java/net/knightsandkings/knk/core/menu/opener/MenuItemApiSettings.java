package net.knightsandkings.knk.core.menu.opener;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import net.knightsandkings.knk.core.domain.settings.KnkMenuItemSettings;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Audience;
import net.knightsandkings.knk.core.menu.opener.MenuOpenerSettings.Click;

/**
 * The API's {@code menuItem} section → {@link MenuOpenerSettings} (PandiO/knk-workspace#871, siege shop DESIGN § 4.3,
 * P26), Bukkit-free. The API validates on save; what still does not fit (a click type, action type or situation this
 * plugin does not know, from a newer API) is reported to {@code warn} and left out, and the rest applies.
 */
public final class MenuItemApiSettings {

    private MenuItemApiSettings() {
    }

    public static MenuOpenerSettings toSettings(KnkMenuItemSettings api, MenuItemSituations situations,
                                                Consumer<String> warn) {
        if (api == null) {
            return null;
        }
        Consumer<String> w = warn != null ? warn : m -> { };
        Audience audience = Audience.parse(api.giveTo());
        if (audience == null) {
            w.accept("menuItem.giveTo '" + api.giveTo() + "' is not all or bedrock; using all");
            audience = Audience.ALL;
        }
        if (api.slot() < 0 || api.slot() > MenuOpenerSettings.MAX_SLOT) {
            w.accept("menuItem.slot " + api.slot() + " is not 0-35; using " + MenuOpenerSettings.DEFAULT_SLOT);
        }
        Map<Click, MenuItemAction> clicks = table("menuItem.clicks", api.clicks(), w);
        Map<String, Map<Click, MenuItemAction>> overrides = new LinkedHashMap<>();
        api.overrides().forEach((situation, table) -> {
            if (situations != null && !situations.isKnown(situation)) {
                w.accept("menuItem.overrides: unknown situation '" + situation + "' (known: "
                    + String.join(", ", situations.names()) + "); left out");
                return;
            }
            overrides.put(situation, table("menuItem.overrides." + situation, table, w));
        });
        return MenuOpenerSettings.withActions(api.enabled(), audience, api.giveOnJoin(), api.keepOnDeath(),
            api.material(), api.name(), api.lore(), api.slot(), api.droppable(), api.movable(), clicks, overrides);
    }

    private static Map<Click, MenuItemAction> table(String where, Map<String, KnkMenuItemSettings.Action> table,
                                                   Consumer<String> warn) {
        Map<Click, MenuItemAction> result = new EnumMap<>(Click.class);
        table.forEach((clickName, action) -> {
            Click click = Click.parse(clickName);
            if (click == null) {
                warn.accept(where + ": unknown click '" + clickName + "'; left out");
                return;
            }
            MenuItemAction.Type type = MenuItemAction.Type.parse(action.type());
            if (type == null) {
                warn.accept(where + "." + clickName + ": unknown action '" + action.type() + "'; does nothing");
                type = MenuItemAction.Type.NONE;
            }
            result.put(click, switch (type) {
                case OPEN_MENU -> MenuItemAction.openMenu(action.menuKey());
                case RUN_COMMAND -> MenuItemAction.runCommand(action.command());
                case NONE -> MenuItemAction.none();
            });
        });
        return result;
    }
}
