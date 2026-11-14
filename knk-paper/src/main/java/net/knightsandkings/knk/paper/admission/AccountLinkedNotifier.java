package net.knightsandkings.knk.paper.admission;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.bukkit.entity.Player;

import net.knightsandkings.knk.core.domain.users.AccountLinkedNotice;
import net.knightsandkings.knk.core.domain.users.PlayerNotification;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Shows {@link PlayerNotification#TYPE_ACCOUNT_LINKED} (invitations DESIGN §5.5): after a link the player learns, right
 * away when online or on their next join, which web account now holds their Minecraft account, so a code given away is
 * noticed.
 *
 * <p>Two templates:
 * <ol>
 *   <li>{@code messages.web-account-registered} (PandiO/knk-workspace#485) for a game-first registration: the player
 *       registered on the website with the link code from the game ({@link AccountLinkedNotice#viaRegister()}).
 *       Placeholders {@code {maskedEmail}}, {@code {name}} (the Minecraft name, which logs in too) and {@code {url}}
 *       ({@code web.public-url}; a line that uses it is left out while that is empty).</li>
 *   <li>{@code admission.account-linked} for every other link (a link code on the Account page, a claim code), and for
 *       an API that doesn't say how. Placeholder {@code {maskedEmail}}.</li>
 * </ol>
 */
public final class AccountLinkedNotifier implements BiConsumer<Player, PlayerNotification> {
    /** {@code messages.web-account-registered}'s default (PandiO/knk-workspace#485). */
    public static final String DEFAULT_WEB_ACCOUNT_REGISTERED =
        "&aYour website account is ready and linked to this Minecraft account.\n"
            + "&aLog in at &e{url} &awith &e{maskedEmail} &aor your Minecraft name &e{name}&a.\n"
            + "&cNot you? Tell staff right away.";

    private final String template;
    private final String registeredTemplate;
    private final String publicUrl;

    public AccountLinkedNotifier(String template) {
        this(template, null, null);
    }

    /**
     * @param template           {@code admission.account-linked}; blank → {@link AdmissionSettings#DEFAULT_ACCOUNT_LINKED}
     * @param registeredTemplate {@code messages.web-account-registered}; blank → {@link #DEFAULT_WEB_ACCOUNT_REGISTERED}
     * @param publicUrl          {@code web.public-url} for {@code {url}}; blank leaves out the lines that use it
     */
    public AccountLinkedNotifier(String template, String registeredTemplate, String publicUrl) {
        this.template = template == null || template.isBlank() ? AdmissionSettings.DEFAULT_ACCOUNT_LINKED : template;
        this.registeredTemplate = registeredTemplate == null || registeredTemplate.isBlank()
            ? DEFAULT_WEB_ACCOUNT_REGISTERED : registeredTemplate;
        String url = publicUrl == null ? "" : publicUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        this.publicUrl = url;
    }

    @Override
    public void accept(Player player, PlayerNotification notification) {
        for (String line : lines(notification)) {
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
        }
    }

    /** The chat lines for {@code notification} ({@code &} colour codes), in order. */
    public List<String> lines(PlayerNotification notification) {
        AccountLinkedNotice notice = notification.accountLinked();
        String masked = notice != null && notice.maskedEmail() != null && !notice.maskedEmail().isBlank()
            ? notice.maskedEmail() : "***";
        if (notice == null || !notice.viaRegister()) {
            return AdmissionMessages.lines(template, Map.of("maskedEmail", masked));
        }
        String name = notification.username() == null ? "" : notification.username();
        String effective = publicUrl.isEmpty() ? withoutUrlLines(registeredTemplate) : registeredTemplate;
        return AdmissionMessages.lines(effective, Map.of("maskedEmail", masked, "name", name, "url", publicUrl));
    }

    private static String withoutUrlLines(String template) {
        StringBuilder kept = new StringBuilder();
        for (String line : template.split("\\R|\\\\n")) {
            if (!line.contains("{url}")) {
                kept.append(line).append('\n');
            }
        }
        return kept.toString();
    }
}
