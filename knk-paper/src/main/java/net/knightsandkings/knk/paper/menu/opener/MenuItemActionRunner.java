package net.knightsandkings.knk.paper.menu.opener;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.menu.opener.MenuItemAction;

/**
 * Runs a menu item action for a player (siege shop L6, PandiO/knk-workspace#871, DESIGN § 4.3.2, P26).
 * <ul>
 *   <li>{@code RunCommand}: the player runs the command (no slash), so its permission and every command rule apply.</li>
 *   <li>{@code OpenMenu}: a menu that needs the player's context opens through its command, which knows that context
 *       and answers when it can't open: {@code main} runs {@code /menu} (as before #871), {@code siege.information}
 *       runs {@code /siege} (the player's own siege Information, else the siege overview, with the chat fallback),
 *       {@code siege.shop} runs {@code /siege shop} (the shop of the running match, or why it isn't open). Any other
 *       key opens that menu template directly; a menu that isn't available says so.</li>
 * </ul>
 */
public final class MenuItemActionRunner implements BiConsumer<Player, MenuItemAction> {

    /** The menu keys that open through a command, and that command. */
    public static final Map<String, String> COMMAND_ROUTES = Map.of(
        MenuItemAction.MAIN_MENU, "menu",
        "siege.information", "siege",
        "siege.shop", "siege shop");

    static final String UNAVAILABLE = "That menu isn't available right now.";

    private final BiPredicate<Player, String> openByKey;
    private final Map<String, Consumer<Player>> routes = new ConcurrentHashMap<>();

    /** @param openByKey opens a menu template by key; false when it isn't available */
    public MenuItemActionRunner(BiPredicate<Player, String> openByKey) {
        this.openByKey = Objects.requireNonNull(openByKey, "openByKey");
        COMMAND_ROUTES.forEach((key, command) -> routes.put(key, player -> player.performCommand(command)));
    }

    /** A feature's own way to open its menu key (replaces a built-in route). */
    public void route(String menuKey, Consumer<Player> opener) {
        routes.put(Objects.requireNonNull(menuKey, "menuKey"), Objects.requireNonNull(opener, "opener"));
    }

    @Override
    public void accept(Player player, MenuItemAction action) {
        if (player == null || action == null || action.isNone()) {
            return;
        }
        switch (action.type()) {
            case RUN_COMMAND -> player.performCommand(action.target());
            case OPEN_MENU -> {
                Consumer<Player> route = routes.get(action.target());
                if (route != null) {
                    route.accept(player);
                } else if (!openByKey.test(player, action.target())) {
                    player.sendMessage(ChatColor.RED + UNAVAILABLE);
                }
            }
            case NONE -> { }
        }
    }
}
