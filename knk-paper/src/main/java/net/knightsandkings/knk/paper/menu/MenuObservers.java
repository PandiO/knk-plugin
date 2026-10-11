package net.knightsandkings.knk.paper.menu;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

/**
 * The registered {@link MenuObserver}s of a {@link MenuService} (KNG-34 link 6). Dispatches to each in
 * registration order; one that throws is logged (once per call) and the others still run. Empty by
 * default, so menus behave exactly as before when nothing is registered.
 */
public final class MenuObservers {

    private static final Logger LOGGER = Logger.getLogger(MenuObservers.class.getName());

    private final List<MenuObserver> observers = new CopyOnWriteArrayList<>();

    public void add(MenuObserver observer) {
        if (observer != null) {
            observers.add(observer);
        }
    }

    public boolean isEmpty() {
        return observers.isEmpty();
    }

    public void opened(Player player, String menuKey, String parentMenuKey) {
        each(o -> o.menuOpened(player, menuKey, parentMenuKey));
    }

    public void back(Player player, String fromMenuKey, String toMenuKey) {
        each(o -> o.menuBack(player, fromMenuKey, toMenuKey));
    }

    public void closed(UUID playerId, String menuKey) {
        if (menuKey != null) {
            each(o -> o.menuClosed(playerId, menuKey));
        }
    }

    public void clicked(Player player, String menuKey, int slot, String itemKey, String clickType) {
        each(o -> o.slotClicked(player, menuKey, slot, itemKey, clickType));
    }

    public void action(Player player, String menuKey, String actionTypeId, int slot, MenuObserver.ActionOutcome outcome) {
        each(o -> o.actionExecuted(player, menuKey, actionTypeId, slot, outcome));
    }

    private void each(Consumer<MenuObserver> call) {
        for (MenuObserver observer : observers) {
            try {
                call.accept(observer);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Menu observer " + observer.getClass().getSimpleName() + " failed", e);
            }
        }
    }
}
