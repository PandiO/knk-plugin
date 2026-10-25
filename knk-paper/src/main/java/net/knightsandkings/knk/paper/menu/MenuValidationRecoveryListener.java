package net.knightsandkings.knk.paper.menu;

import net.knightsandkings.knk.paper.events.ApiConnectivityChangedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Objects;

/**
 * Playtest 2026-10-24: when the API comes back after an outage (KNG-115 recovery transition), the menus the
 * startup validation couldn't check are validated right away instead of at the next timed retry.
 */
public final class MenuValidationRecoveryListener implements Listener {

    private final MenuService menuService;

    public MenuValidationRecoveryListener(MenuService menuService) {
        this.menuService = Objects.requireNonNull(menuService, "menuService");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConnectivity(ApiConnectivityChangedEvent event) {
        if (event.isRecovery()) {
            menuService.revalidateAfterRecovery();
        }
    }
}
