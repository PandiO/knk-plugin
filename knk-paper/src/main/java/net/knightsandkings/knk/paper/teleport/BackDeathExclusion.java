package net.knightsandkings.knk.paper.teleport;

import org.bukkit.entity.Player;

/**
 * Says a death must not give a {@code /back} (docs/specs/teleport Phase 7; developer decision Q5:
 * "not after a siege death"). Registered through {@link BackService#registerDeathExclusion} (or
 * {@code KnKPlugin.registerBackDeathExclusion}) the same way {@link TeleportRestriction}s are, so the
 * teleport code doesn't depend on the siege code: the siege branch registers
 * {@code player -> siegeService.activeLobbyOf(player.getUniqueId()).isPresent()} next to its
 * {@code SiegeTeleportRestriction}.
 * <p>
 * Asked on the main thread at {@code PlayerDeathEvent} priority LOWEST - before the siege's own
 * death handling (HIGHEST) can end the match or move the player - so "was this a match death" is
 * answered from the state the player died in. An excluded death also wipes any older death, so
 * {@code /back} never leads anywhere after a siege death. A throwing exclusion counts as "excluded"
 * (fail closed). Must be quick and must not block.
 */
@FunctionalInterface
public interface BackDeathExclusion {

    /** True when {@code player}'s death, happening right now, must not be recorded for {@code /back}. */
    boolean excludes(Player player);
}
