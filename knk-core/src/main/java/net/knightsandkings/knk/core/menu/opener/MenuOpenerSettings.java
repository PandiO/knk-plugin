package net.knightsandkings.knk.core.menu.opener;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The menu item (alpha test run 1, PandiO/knk-workspace#160): an item in the player's inventory that opens
 * {@code /menu} when clicked, so console and Bedrock players, for whom chat commands are hard to type, get into
 * the menus. Read from {@code menus.menu-item} in the plugin's {@code config.yml}; Bukkit-free.
 *
 * @param enabled     whether the item exists at all; off takes it out of every inventory at the next join
 * @param audience    who gets it: everyone, or Bedrock (Floodgate) players only
 * @param giveOnJoin  put it in the inventory at every join (when it is missing)
 * @param keepOnDeath give it back at respawn; it never drops on the ground either way
 * @param material    the item's material name ({@code NETHER_STAR})
 * @param name        display name, {@code &} colour codes
 * @param lore        lore lines, {@code &} colour codes
 * @param slot        the inventory slot it goes to: 0-8 hotbar, 9-35 main inventory
 * @param openOn      which clicks open the menu
 * @param droppable   the player may drop it; it is then gone (never on the ground) until the next join or respawn
 * @param movable     the player may move it within their own inventory; never into another inventory
 */
public record MenuOpenerSettings(boolean enabled, Audience audience, boolean giveOnJoin, boolean keepOnDeath,
                                 String material, String name, List<String> lore, int slot, Set<Click> openOn,
                                 boolean droppable, boolean movable) {

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

    /** A click that can open the menu. */
    public enum Click {
        /** Right-click (use) in the air or on a block that does nothing on its own. */
        RIGHT_CLICK,
        /** Left-click (attack / swing) in the air or on a block. */
        LEFT_CLICK,
        /** A click on the item in the player's own open inventory (only when the item is not movable). */
        INVENTORY_CLICK;

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
    }

    /** The defaults of {@code config.yml}: on for everyone, star in the last hotbar slot, any click opens the menu. */
    public static MenuOpenerSettings defaults() {
        return new MenuOpenerSettings(true, Audience.ALL, true, true, DEFAULT_MATERIAL, DEFAULT_NAME, DEFAULT_LORE,
            DEFAULT_SLOT, EnumSet.allOf(Click.class), false, false);
    }

    /** Switched off: nobody has the item, existing ones are taken away at the next join. */
    public static MenuOpenerSettings disabled() {
        MenuOpenerSettings d = defaults();
        return new MenuOpenerSettings(false, d.audience(), d.giveOnJoin(), d.keepOnDeath(), d.material(), d.name(),
            d.lore(), d.slot(), d.openOn(), d.droppable(), d.movable());
    }

    public boolean opensOn(Click click) {
        return openOn.contains(click);
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
