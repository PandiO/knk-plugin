package net.knightsandkings.knk.core.domain.users;

/**
 * Payload of {@link PlayerNotification#TYPE_ACCOUNT_LINKED} (knk-web-api's {@code AccountLinkedNotificationDto}): the
 * web account the player's Minecraft account was just linked to, its address masked as {@code st***@example.com}
 * ({@code ***} when it has none), and how the link came about ({@code via}, PandiO/knk-workspace#485):
 * {@link #VIA_REGISTER} for a game-first registration on the website with the link code from the game,
 * {@link #VIA_LINK} for a link code entered on the Account page, {@link #VIA_CLAIM} for a claim code from the admission
 * gate; null from an API that doesn't send it.
 */
public record AccountLinkedNotice(String maskedEmail, String via) {
    public static final String VIA_REGISTER = "register";
    public static final String VIA_LINK = "link";
    public static final String VIA_CLAIM = "claim";

    /** Without {@code via} (an API from before PandiO/knk-workspace#485). */
    public AccountLinkedNotice(String maskedEmail) {
        this(maskedEmail, null);
    }

    /** The player registered on the website with the link code from the game (game-first registration). */
    public boolean viaRegister() {
        return VIA_REGISTER.equalsIgnoreCase(via);
    }
}
