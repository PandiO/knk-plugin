package net.knightsandkings.knk.paper.duel.ui;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * The duel menus P6 (KNG-220) builds on the menu runtime, as {@code /duels} and {@code /arena} open them (arena duels
 * DESIGN §6.1, §9.2–9.3). P6's {@code DuelMenus} plugs in with {@code KnKPlugin.setDuelMenuPort}; while none is set
 * (or an open returns false) the commands answer in chat. Main thread.
 */
public interface DuelMenuPort {

    /** {@code /duels}: live matches ({@code duel_matches}). */
    boolean openMatches(Player player);

    /** {@code /duels view <token>}: the match detail ({@code duel_match_detail}). */
    boolean openMatch(Player player, UUID token);

    /** {@code /arena [name]} ({@code arena_main}); null = the arena the player stands in. */
    boolean openArena(Player player, String nameOrKey);

    /** {@code /duels spectate <arenaId>}: the spectator area's domain warp. */
    void spectate(Player player, int arenaId);
}
