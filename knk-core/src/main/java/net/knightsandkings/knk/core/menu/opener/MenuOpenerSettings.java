package net.knightsandkings.knk.core.menu.opener;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The menu item (alpha test run 1, PandiO/knk-workspace#160): an item in the player's inventory that opens
 * {@code /menu} when clicked, so console and Bedrock players, for whom chat commands are hard to type, get into
 * the menus. Bukkit-free.
 * <p>
 * Since PandiO/knk-workspace#871 (siege shop DESIGN § 4.3, § 5.7, P26) the settings come from the API's game
 * settings ({@code menuItem}), with one action per click and per-situation overrides; the {@code menus.menu-item}
 * keys of {@code config.yml} are the startup fallback while the API has not answered. Settings read from the file
 * have no overrides, and every click in {@code open-on} opens the hub menu as before (a shift-click counts as the
 * plain click, as it always did).
 *
 * @param enabled     whether the item exists at all; off takes it out of every inventory at the next join
 * @param audience    who gets it: everyone, or Bedrock (Floodgate) players only
 * @param giveOnJoin  put it in the inventory at every join (when it is missing)
 * @param keepOnDeath give it back at respawn; it never drops on the ground either way
 * @param material    the item's material name ({@code NETHER_STAR})
 * @param name        display name, {@code &} colour codes
 * @param lore        lore lines, {@code &} colour codes
 * @param slot        the inventory slot it goes to: 0-8 hotbar, 9-35 main inventory
 * @param openOn      which clicks do something in the default table (with the file: which clicks open the menu)
 * @param droppable   the player may drop it; it is then gone (never on the ground) until the next join or respawn
 * @param movable     the player may move it within their own inventory; never into another inventory
 * @param clicks      the default click table; a click not in it does nothing. Null: derived from {@code openOn}
 *                    (every click there opens {@link MenuItemAction#MAIN_MENU}, shift-clicks follow their plain click)
 * @param overrides   situation ({@link MenuItemSituations}) → partial click table; a click an override leaves out
 *                    uses {@code clicks}. Never null
 */
public record MenuOpenerSettings(boolean enabled, Audience audience, boolean giveOnJoin, boolean keepOnDeath,
                                 String material, String name, List<String> lore, int slot, Set<Click> openOn,
                                 boolean droppable, boolean movable, Map<Click, MenuItemAction> clicks,
                                 Map<String, Map<Click, MenuItemAction>> overrides) {

    /** Who gets the item. */
    public enum Audience {
        ALL,
        BEDROCK;

        /** {@code all} / {@code bedrock}, any case; null for anything else. */
        public static Audience parse(String text) {
            if (text == null) {
                return null;
            }
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "all" -> ALL;
                case "bedrock" -> BEDROCK;
                default -> null;
            };
        }
    }

    /** A click with the item (the API's click types, PandiO/knk-workspace#870). */
    public enum Click {
        /** Right-click (use) in the air or on a block that does nothing on its own. */
        RIGHT_CLICK,
        /** Left-click (attack / swing) in the air or on a block. */
        LEFT_CLICK,
        /** A click on the item in the player's own open inventory (only when the item is not movable). */
        INVENTORY_CLICK,
        /** Right-click while sneaking (also on a door or chest, which a plain right-click leaves alone). */
        SHIFT_RIGHT_CLICK,
        /** Left-click while sneaking. */
        SHIFT_LEFT_CLICK;

        /** The click this is while the player sneaks: the shift variant of a left or right click. */
        public Click sneaking() {
            return switch (this) {
                case RIGHT_CLICK -> SHIFT_RIGHT_CLICK;
                case LEFT_CLICK -> SHIFT_LEFT_CLICK;
                default -> this;
            };
        }

        /** The enum name, any case, {@code -} for {@code _}; null for anything else. */
        public static Click parse(String text) {
            if (text == null) {
                return null;
            }
            try {
                return Click.valueOf(text.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    public static final String DEFAULT_MATERIAL = "NETHER_STAR";
    public static final String DEFAULT_NAME = "&6&lMenu &7(click to open)";
    public static final List<String> DEFAULT_LORE = List.of("&7Opens the Knights & Kings menu (&e/menu&7).");
    public static final int DEFAULT_SLOT = 8;
    /** Player inventory storage slots: 0-8 hotbar, 9-35 main inventory. */
    public static final int MAX_SLOT = 35;

    public MenuOpenerSettings {
        audience = audience == null ? Audience.ALL : audience;
        material = material == null || material.isBlank() ? DEFAULT_MATERIAL : material.trim().toUpperCase(Locale.ROOT);
        name = name == null || name.isBlank() ? DEFAULT_NAME : name;
        lore = lore == null ? List.of() : List.copyOf(lore);
        slot = slot < 0 || slot > MAX_SLOT ? DEFAULT_SLOT : slot;
        openOn = openOn == null || openOn.isEmpty() ? EnumSet.noneOf(Click.class) : EnumSet.copyOf(openOn);
        clicks = clicks == null ? fromOpenOn(openOn) : copyTable(clicks);
        overrides = copyOverrides(overrides);
    }

    /** The file's shape ({@code menus.menu-item}): every click in {@code openOn} opens the hub menu, no overrides. */
    public MenuOpenerSettings(boolean enabled, Audience audience, boolean giveOnJoin, boolean keepOnDeath,
                              String material, String name, List<String> lore, int slot, Set<Click> openOn,
                              boolean droppable, boolean movable) {
        this(enabled, audience, giveOnJoin, keepOnDeath, material, name, lore, slot, openOn, droppable, movable, null,
            null);
    }

    /**
     * The API's shape: a click table and overrides; {@code openOn} becomes the clicks that do something in the
     * default table.
     */
    public static MenuOpenerSettings withActions(boolean enabled, Audience audience, boolean giveOnJoin,
                                                 boolean keepOnDeath, String material, String name, List<String> lore,
                                                 int slot, boolean droppable, boolean movable,
                                                 Map<Click, MenuItemAction> clicks,
                                                 Map<String, Map<Click, MenuItemAction>> overrides) {
        Map<Click, MenuItemAction> table = clicks == null ? Map.of() : clicks;
        Set<Click> active = EnumSet.noneOf(Click.class);
        table.forEach((click, action) -> {
            if (click != null && action != null && !action.isNone()) {
                active.add(click);
            }
        });
        return new MenuOpenerSettings(enabled, audience, giveOnJoin, keepOnDeath, material, name, lore, slot, active,
            droppable, movable, table, overrides);
    }

    /**
     * Today's rule: a click in {@code open-on} opens the hub menu; a shift-click is the plain click unless
     * {@code open-on} names the shift-click itself.
     */
    static Map<Click, MenuItemAction> fromOpenOn(Set<Click> openOn) {
        Map<Click, MenuItemAction> table = new EnumMap<>(Click.class);
        MenuItemAction open = MenuItemAction.openMenu(MenuItemAction.MAIN_MENU);
        for (Click click : Click.values()) {
            boolean on = openOn.contains(click)
                || (click == Click.SHIFT_RIGHT_CLICK && openOn.contains(Click.RIGHT_CLICK))
                || (click == Click.SHIFT_LEFT_CLICK && openOn.contains(Click.LEFT_CLICK));
            if (on) {
                table.put(click, open);
            }
        }
        return Collections.unmodifiableMap(table);
    }

    private static Map<Click, MenuItemAction> copyTable(Map<Click, MenuItemAction> table) {
        Map<Click, MenuItemAction> copy = new EnumMap<>(Click.class);
        table.forEach((click, action) -> {
            if (click != null && action != null) {
                copy.put(click, action);
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Map<Click, MenuItemAction>> copyOverrides(Map<String, Map<Click, MenuItemAction>> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<Click, MenuItemAction>> copy = new LinkedHashMap<>();
        overrides.forEach((situation, table) -> {
            if (situation != null && !situation.isBlank() && table != null && !table.isEmpty()) {
                copy.put(situation.trim(), copyTable(table));
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    /**
     * The defaults of {@code config.yml}: on for everyone, star in the last hotbar slot, any click opens the menu
     * ({@code open-on} lists the three plain clicks; the shift-clicks follow them).
     */
    public static MenuOpenerSettings defaults() {
        return new MenuOpenerSettings(true, Audience.ALL, true, true, DEFAULT_MATERIAL, DEFAULT_NAME, DEFAULT_LORE,
            DEFAULT_SLOT, EnumSet.of(Click.RIGHT_CLICK, Click.LEFT_CLICK, Click.INVENTORY_CLICK), false, false);
    }

    /** Switched off: nobody has the item, existing ones are taken away at the next join. */
    public static MenuOpenerSettings disabled() {
        MenuOpenerSettings d = defaults();
        return new MenuOpenerSettings(false, d.audience(), d.giveOnJoin(), d.keepOnDeath(), d.material(), d.name(),
            d.lore(), d.slot(), d.openOn(), d.droppable(), d.movable());
    }

    /** Whether the click does something in the default table. */
    public boolean opensOn(Click click) {
        MenuItemAction action = clicks.get(click);
        return action != null && !action.isNone();
    }

    /** Whether a player gets the item: enabled, and for {@link Audience#BEDROCK} only Bedrock players. */
    public boolean wantedFor(boolean bedrockPlayer) {
        return enabled && (audience == Audience.ALL || bedrockPlayer);
    }

    /**
     * What makes an item the current version of the menu item: material, name and lore. An item a player still holds
     * from before a config change has another fingerprint and is replaced in place.
     */
    public String fingerprint() {
        return Integer.toHexString(Objects.hash(material, name, lore));
    }

    @Override
    public Set<Click> openOn() {
        return openOn.isEmpty() ? EnumSet.noneOf(Click.class) : EnumSet.copyOf(openOn);
    }
}
