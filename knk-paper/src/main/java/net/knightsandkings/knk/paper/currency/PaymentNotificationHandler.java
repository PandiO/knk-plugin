package net.knightsandkings.knk.paper.currency;

import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.currency.CurrencyFormat;
import net.knightsandkings.knk.core.domain.currency.PaymentNotice;
import net.knightsandkings.knk.core.domain.users.PlayerNotification;

/**
 * Shows a {@code PaymentReceived} notification (currency Phase 3): the API queues one for every
 * completed /pay, and PlayerNotificationPoller hands it here once the recipient is online - within
 * a poll interval when they already are, otherwise on their next join (DESIGN.md D13). Then
 * re-reads the recipient from the API so cached balances are the server's current ones (the
 * notification's own balance may be older than that by the time they join).
 */
public final class PaymentNotificationHandler {

    private final CurrencySettings settings;
    private final Consumer<UUID> refreshUser;

    public PaymentNotificationHandler(CurrencySettings settings, Consumer<UUID> refreshUser) {
        this.settings = settings;
        this.refreshUser = refreshUser;
    }

    /** Main thread. */
    public void handle(Player player, PlayerNotification notification) {
        PaymentNotice payment = notification.payment();
        if (payment == null) {
            return;
        }
        player.sendMessage(settings.message("pay-received",
            "amount", CurrencyFormat.amount(payment.amount()),
            "currency", CurrencyFormat.name(payment.currency(), payment.amount()),
            "player", payment.fromUsername() != null ? payment.fromUsername() : "another player"));
        if (refreshUser != null) {
            refreshUser.accept(player.getUniqueId());
        }
    }
}
