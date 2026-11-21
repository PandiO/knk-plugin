package net.knightsandkings.knk.core.menu.opener;

import java.util.Locale;
import java.util.Objects;

/**
 * What one click with the menu item does (siege shop DESIGN § 4.3.2, P26; PandiO/knk-workspace#871): open a menu by
 * its key, run a player command (no leading slash), or nothing. Bukkit-free.
 *
 * @param type   the kind of action; never null
 * @param target the menu key for {@link Type#OPEN_MENU}, the command for {@link Type#RUN_COMMAND}, null for
 *               {@link Type#NONE}
 */
public record MenuItemAction(Type type, String target) {

    /** The hub menu, which {@code /menu} opens: today's (and the config file's) only action. */
    public static final String MAIN_MENU = "main";

    private static final MenuItemAction NOTHING = new MenuItemAction(Type.NONE, null);

    /** The action types of the API ({@code OpenMenu}, {@code RunCommand}, {@code None}). */
    public enum Type {
        OPEN_MENU("OpenMenu"),
        RUN_COMMAND("RunCommand"),
        NONE("None");

        private final String apiName;

        Type(String apiName) {
            this.apiName = apiName;
        }

        public String apiName() {
            return apiName;
        }

        /** The API name ({@code OpenMenu}, {@code RunCommand}, {@code None}) or the enum name, any case; null otherwise. */
        public static Type parse(String text) {
            if (text == null) {
                return null;
            }
            String t = text.trim().replace("_", "").toLowerCase(Locale.ROOT);
            for (Type type : values()) {
                if (type.apiName.toLowerCase(Locale.ROOT).equals(t)) {
                    return type;
                }
            }
            return null;
        }
    }

    public MenuItemAction {
        Objects.requireNonNull(type, "type");
        target = target == null ? null : target.trim();
        if (type == Type.RUN_COMMAND && target != null && target.startsWith("/")) {
            target = target.substring(1).trim();
        }
        if (type == Type.NONE || target == null || target.isEmpty()) {
            if (type != Type.NONE) {
                type = Type.NONE; // an open or run without its key or command does nothing
            }
            target = null;
        }
    }

    public static MenuItemAction openMenu(String menuKey) {
        return new MenuItemAction(Type.OPEN_MENU, menuKey);
    }

    public static MenuItemAction runCommand(String command) {
        return new MenuItemAction(Type.RUN_COMMAND, command);
    }

    public static MenuItemAction none() {
        return NOTHING;
    }

    public boolean isNone() {
        return type == Type.NONE;
    }
}
