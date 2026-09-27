package net.knightsandkings.knk.paper.currency;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.currency.CurrencyAlertNotice;
import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Shows a {@code CurrencyAlert} notification (currency Phase 5, DESIGN.md §3.9): knk-web-api's
 * currency monitor raised an anomaly alert, and every online staff member holding
 * knk.admin.currency.alerts (checked through KnkPermissible) gets one line - clickable to open
 * {@code /knk currency alerts} - plus a warning when the alert switched player transfers off. The
 * console always logs it. Best-effort: staff who are offline see the alert on the web alerts page
 * or with {@code /knk currency alerts}, where it stays until acknowledged.
 */
public final class CurrencyAlertNotifier {
    private static final Logger LOGGER = Logger.getLogger(CurrencyAlertNotifier.class.getName());
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final CurrencySettings settings;
    private final PlayerCurrencyService.PermissionCheck permissions;
    private final Supplier<Collection<? extends Player>> onlinePlayers;
    private final Executor mainThread;

    public CurrencyAlertNotifier(CurrencySettings settings, PlayerCurrencyService.PermissionCheck permissions,
                                 Supplier<Collection<? extends Player>> onlinePlayers, Executor mainThread) {
        this.settings = settings;
        this.permissions = permissions;
        this.onlinePlayers = onlinePlayers;
        this.mainThread = mainThread;
    }

    /** Main thread (PlayerNotificationPoller). The node checks may complete later; messages are sent on the main thread. */
    public void handle(PlayerNotification notification) {
        CurrencyAlertNotice alert = notification.currencyAlert();
        if (alert == null) {
            return;
        }
        LOGGER.warning("Currency alert #" + alert.alertId() + " " + alert.rule() + " (" + alert.severity() + "): " + alert.summary()
            + (alert.transfersDisabled().isEmpty() ? "" : " - transfers switched off for " + String.join(", ", alert.transfersDisabled())));

        String line = settings.message("alert-notice",
            "severity", alert.severity(),
            "rule", alert.rule(),
            "name", alert.ruleName() != null ? "(" + alert.ruleName() + ")" : "",
            "summary", (alert.username() != null && !alert.username().isBlank() ? alert.username() + ": " : "") + alert.summary());
        String transfersOff = alert.transfersDisabled().isEmpty()
            ? null
            : settings.message("alert-notice-transfers-off", "currencies", String.join(", ", alert.transfersDisabled()));
        Component message = LEGACY.deserialize(line)
            .clickEvent(ClickEvent.runCommand("/knk currency alerts"))
            .hoverEvent(HoverEvent.showText(Component.text("Alert #" + alert.alertId() + " - click to list the open currency alerts")));

        for (Player player : List.copyOf(onlinePlayers.get())) {
            permissions.has(player, PlayerCurrencyService.CURRENCY_ALERTS_NODE)
                .thenAccept(decision -> {
                    if (decision != net.knightsandkings.knk.core.domain.permissions.PermissionDecision.ALLOWED) {
                        return;
                    }
                    mainThread.execute(() -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        player.sendMessage(message);
                        if (transfersOff != null) {
                            player.sendMessage(transfersOff);
                        }
                    });
                })
                .exceptionally(ex -> {
                    LOGGER.fine("Could not check " + PlayerCurrencyService.CURRENCY_ALERTS_NODE + " for " + player.getName() + ": " + ex);
                    return null;
                });
        }
    }
}
