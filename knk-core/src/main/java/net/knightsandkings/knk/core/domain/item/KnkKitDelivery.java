package net.knightsandkings.knk.core.domain.item;

/**
 * A staff grant waiting for its in-game hand-over (PandiO/knk-workspace#261): a kit given from outside the game
 * server, e.g. "Grant" on the web app's player profile. {@code loadout} is what to place, the same shape a claim
 * returns. Mirrors knk-web-api's {@code KitDeliveryDto}.
 */
public record KnkKitDelivery(int claimId, int kitId, String kitName, KnkKitClaimResult loadout) {}
