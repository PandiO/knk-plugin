package net.knightsandkings.knk.core.domain.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The game settings' {@code menuItem} section as the API sends it (knk-web-api {@code MenuItemSettingsDto},
 * PandiO/knk-workspace#870; siege shop DESIGN § 4.3): the look and rules of the menu item, a click table (click
 * type → action) and per-situation partial click tables. Kept as plain strings, so an older or newer value never
 * breaks the game settings cache; {@code MenuItemApiSettings} turns it into the plugin's settings.
 *
 * @param clicks    click type ({@code LEFT_CLICK}, ...) → action; never null
 * @param overrides situation ({@code siege.match}, ...) → click type → action; never null
 */
public record KnkMenuItemSettings(boolean enabled, String giveTo, boolean giveOnJoin, boolean keepOnDeath,
                                  String material, String name, List<String> lore, int slot, boolean droppable,
                                  boolean movable, Map<String, Action> clicks,
                                  Map<String, Map<String, Action>> overrides) {

    /**
     * One click's action: {@code type} {@code OpenMenu} (with {@code menuKey}), {@code RunCommand} (with
     * {@code command}, no slash) or {@code None}.
     */
    public record Action(String type, String menuKey, String command) {
    }

    public KnkMenuItemSettings {
        lore = lore == null ? List.of() : lore.stream().map(l -> l == null ? "" : l).toList();
        clicks = clicks == null ? Map.of() : copy(clicks);
        Map<String, Map<String, Action>> o = new LinkedHashMap<>();
        if (overrides != null) {
            overrides.forEach((situation, table) -> {
                if (situation != null && table != null) {
                    o.put(situation, copy(table));
                }
            });
        }
        overrides = java.util.Collections.unmodifiableMap(o);
    }

    private static Map<String, Action> copy(Map<String, Action> table) {
        Map<String, Action> c = new LinkedHashMap<>();
        table.forEach((click, action) -> {
            if (click != null && action != null) {
                c.put(click, action);
            }
        });
        return java.util.Collections.unmodifiableMap(c);
    }
}
