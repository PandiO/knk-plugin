package net.knightsandkings.knk.core.minigame;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.knightsandkings.knk.core.teleport.TeleportDenial;

/**
 * What the plugin-wide hooks need to know about one minigame's members (arena duels DESIGN §11): the enchantment
 * safezone, statistics context, headshots, discovery, navigation, outage release, {@code /hub}, the domain-access
 * respawn correction, lootbox deliveries, scoreboards and the teleport guards. Each minigame (siege, duel, later
 * KNG-72 / KNG-50) has one adapter; {@link MinigameRegistry} composes them, so none of those hooks names a minigame.
 * <p>
 * The defaults follow {@link Participation.Stage}; an adapter overrides them where its rules differ. Every method
 * is asked live, on the thread of the hook calling it (mostly the main thread).
 */
public interface MinigameParticipation {

    /**
     * The minigame's id: lower case, also the statistics context of its matches and the noun in shared messages
     * ("You are in a siege.").
     */
    String id();

    /** The player's membership, or empty when they aren't a member. */
    Optional<Participation> participationOf(UUID playerId);

    /** True when this minigame's rules allow {@code attacker} to hit {@code victim} (KNG-11 enchantment safezone). */
    default boolean allowsCombat(UUID attacker, UUID victim) {
        return false;
    }

    /** Damage multiplier of a headshot between two members (1.0 = no change). */
    default double headshotMultiplier(UUID attacker, UUID victim) {
        return 1.0;
    }

    /**
     * An API-outage action is about to move or kick this player (hub DESIGN §16.4): free them, ending or aborting
     * their match as the minigame decides.
     *
     * @return true when the player may be moved now (not a member, or just released); false = try again later.
     *         The default lets every player go.
     */
    default boolean releaseForOutage(UUID playerId) {
        return true;
    }

    /** The API is back after an outage: resume what {@link #releaseForOutage} stopped. */
    default void resumeAfterOutage() {
    }

    /** Hold back lootbox tokens and other item deliveries until the member's own inventory is back. */
    default boolean holdsLootDrops(UUID playerId) {
        return participationOf(playerId).map(p -> p.stage().isAway()).orElse(false);
    }

    /** Keep the member's minigame scoreboard when rank or salary refreshes reset everyone's board. */
    default boolean keepsOwnScoreboard(UUID playerId) {
        return participationOf(playerId).map(p -> p.stage().isAway()).orElse(false);
    }

    /** A death of this member gives no {@code /back} (teleport developer decision Q5). */
    default boolean excludesBackOnDeath(UUID playerId) {
        return participationOf(playerId).map(p -> p.stage().isAway()).orElse(false);
    }

    /**
     * Refuse a teleport-engine move of a member, or a request whose other side is one. The minigame's own moves
     * don't go through the engine and never reach this.
     */
    default Optional<TeleportDenial> teleportRefusal(MinigameTeleportAttempt attempt) {
        return Optional.empty();
    }

    /** Nodes that may lift {@link #teleportRefusal} (the engine resolves them before asking). */
    default Set<String> teleportBypassNodes() {
        return Set.of();
    }
}
