package net.knightsandkings.knk.paper.admission;

import java.util.Map;
import java.util.function.BiConsumer;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.users.AccountLinkedNotice;
import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Shows {@link PlayerNotification#TYPE_ACCOUNT_LINKED} (invitations DESIGN §5.5): after a link the player learns, on
 * this join or their next one, which web account now holds their Minecraft account, so a code given away is noticed.
 * Template {@code admission.account-linked}, placeholder {@code {maskedEmail}}.
 */
public final class AccountLinkedNotifier implements BiConsumer<Player, PlayerNotification> {
    private final String template;

    public AccountLinkedNotifier(String template) {
        this.template = template == null || template.isBlank() ? AdmissionSettings.DEFAULT_ACCOUNT_LINKED : template;
    }

    @Override
    public void accept(Player player, PlayerNotification notification) {
        AccountLinkedNotice notice = notification.accountLinked();
        String masked = notice != null && notice.maskedEmail() != null && !notice.maskedEmail().isBlank()
            ? notice.maskedEmail() : "***";
        for (String line : AdmissionMessages.lines(template, Map.of("maskedEmail", masked))) {
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
        }
    }
}
