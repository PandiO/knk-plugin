package net.knightsandkings.knk.paper.analytics;

import java.util.UUID;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.analytics.MenuFunnelCounter;
import net.knightsandkings.knk.core.analytics.WorldAnalyticsWindow;
import net.knightsandkings.knk.paper.menu.MenuObserver;

/**
 * Menu funnels (KNG-34 link 7, DESIGN.md D11, IMPLEMENTATION_PLAN.md §1.4): a {@link MenuObserver}
 * registered with {@code MenuService.addObserver} that counts, per menu key, {@code opened},
 * {@code action:<actionTypeId>} by outcome, {@code back} and {@code closed}. Main thread; anonymous
 * (the player is never looked at). Clicks without an action are not a funnel step.
 */
public final class MenuFunnelRecorder implements MenuObserver {

    private final WorldAnalyticsWindow window;

    public MenuFunnelRecorder(WorldAnalyticsWindow window) {
        this.window = window;
    }

    @Override
    public void menuOpened(Player player, String menuKey, String parentMenuKey) {
        window.menuOpened(menuKey);
    }

    /** Counted on the menu that was left. */
    @Override
    public void menuBack(Player player, String fromMenuKey, String toMenuKey) {
        window.menuBack(fromMenuKey);
    }

    @Override
    public void menuClosed(UUID playerId, String menuKey) {
        window.menuClosed(menuKey);
    }

    @Override
    public void actionExecuted(Player player, String menuKey, String actionTypeId, int slot, ActionOutcome outcome) {
        window.menuAction(menuKey, actionTypeId, outcome(outcome));
    }

    static String outcome(ActionOutcome outcome) {
        if (outcome == null) {
            return MenuFunnelCounter.FAILED;
        }
        return switch (outcome) {
            case SUCCEEDED -> MenuFunnelCounter.SUCCEEDED;
            case DENIED -> MenuFunnelCounter.DENIED;
            case FAILED -> MenuFunnelCounter.FAILED;
        };
    }
}
