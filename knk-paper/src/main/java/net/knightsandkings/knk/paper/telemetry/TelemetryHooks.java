package net.knightsandkings.knk.paper.telemetry;

import java.util.UUID;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrant;
import net.knightsandkings.knk.core.domain.discovery.DiscoveryGrantResult;
import net.knightsandkings.knk.core.telemetry.ApiFailureObserver;
import net.knightsandkings.knk.core.telemetry.TelemetryEvent;
import net.knightsandkings.knk.core.telemetry.TelemetryEventNames;
import net.knightsandkings.knk.paper.menu.MenuObserver;
import net.knightsandkings.knk.paper.statistics.StatisticsService;

/**
 * Turns the plugin's existing hook points into diagnostic events (KNG-34 link 6, DESIGN.md §F.12):
 * menus ({@code menu.opened}, {@code menu.action}, enhanced {@code menu.click}), AFK changes
 * ({@code session.afk_changed}), presented discovery grants ({@code discovery.granted}) and failed
 * plugin → API calls ({@code api.call_failed}). Registered only when {@code telemetry.enabled} is true.
 */
public final class TelemetryHooks implements MenuObserver, StatisticsService.AfkObserver, ApiFailureObserver {

    private final TelemetryEmitter emitter;

    public TelemetryHooks(TelemetryEmitter emitter) {
        this.emitter = emitter;
    }

    // ===== menus =====

    @Override
    public void menuOpened(Player player, String menuKey, String parentMenuKey) {
        emitter.event(TelemetryEventNames.MENU_OPENED)
            .player(player.getUniqueId())
            .outcome(TelemetryEvent.Outcome.SUCCEEDED)
            .object("menu", menuKey)
            .put("menuKey", menuKey)
            .put("parentMenuKey", parentMenuKey)
            .emit();
    }

    @Override
    public void slotClicked(Player player, String menuKey, int slot, String itemKey, String clickType) {
        UUID id = player.getUniqueId();
        if (!emitter.isEnhanced(id)) {
            return;
        }
        emitter.event(TelemetryEventNames.MENU_CLICK)
            .player(id)
            .object("menu", menuKey)
            .put("menuKey", menuKey)
            .put("slot", slot)
            .put("itemKey", itemKey)
            .put("clickType", clickType)
            .emit();
    }

    @Override
    public void actionExecuted(Player player, String menuKey, String actionTypeId, int slot, ActionOutcome outcome) {
        emitter.event(TelemetryEventNames.MENU_ACTION)
            .player(player.getUniqueId())
            .outcome(switch (outcome) {
                case SUCCEEDED -> TelemetryEvent.Outcome.SUCCEEDED;
                case DENIED -> TelemetryEvent.Outcome.DENIED;
                case FAILED -> TelemetryEvent.Outcome.FAILED;
            })
            .reason(outcome == ActionOutcome.DENIED ? "condition_failed" : outcome == ActionOutcome.FAILED ? "handler_failed" : null)
            .object("menu", menuKey)
            .put("menuKey", menuKey)
            .put("actionType", actionTypeId)
            .put("slot", slot)
            .emit();
    }

    // ===== AFK =====

    @Override
    public void afkChanged(Player player, boolean afk, boolean manual) {
        emitter.event(TelemetryEventNames.SESSION_AFK_CHANGED)
            .player(player.getUniqueId())
            .outcome(TelemetryEvent.Outcome.INFO)
            .reason(afk ? (manual ? "afk_manual" : "afk_idle") : (manual ? "back_manual" : "back_activity"))
            .put("afk", afk)
            .put("manual", manual)
            .emit();
    }

    // ===== discoveries =====

    /** A discovery result the player was shown (live or delivered late): one event per granted domain. */
    public void discoveriesGranted(Player player, DiscoveryGrantResult result) {
        if (result == null || result.granted() == null) {
            return;
        }
        for (DiscoveryGrant grant : result.granted()) {
            emitter.event(TelemetryEventNames.DISCOVERY_GRANTED)
                .player(player.getUniqueId())
                .outcome(TelemetryEvent.Outcome.SUCCEEDED)
                .object("domain", grant.domainId())
                .put("domainId", grant.domainId())
                .put("domainType", grant.domainType())
                .emit();
        }
    }

    // ===== API failures =====

    @Override
    public void callFailed(String method, String routeTemplate, int status, String exceptionType, String correlationId) {
        emitter.event(TelemetryEventNames.API_CALL_FAILED)
            .correlation(correlationId)
            .outcome(TelemetryEvent.Outcome.FAILED)
            .reason(status == 0 ? "io_error" : "http_" + status)
            .put("method", method)
            .put("route", routeTemplate)
            .put("status", status)
            .put("exceptionType", exceptionType)
            .emit();
    }
}
