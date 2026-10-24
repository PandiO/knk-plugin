package net.knightsandkings.knk.core.domain.users;

/**
 * Payload of {@link PlayerNotification#TYPE_ACCOUNT_LINKED} (knk-web-api's {@code AccountLinkedNotificationDto}): the
 * web account the player's Minecraft account was just linked to, its address masked as {@code st***@example.com}
 * ({@code ***} when it has none).
 */
public record AccountLinkedNotice(String maskedEmail) {
}
