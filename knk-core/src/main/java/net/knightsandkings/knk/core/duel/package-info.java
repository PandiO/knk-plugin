/**
 * Arena duels: pure rules (arena duels DESIGN §5, §6, §8.1, §8.4; plan Phase 3). Bukkit-free and
 * clock-free: time is passed in as the server tick ({@link net.knightsandkings.knk.core.duel.DuelTicks}),
 * players as {@link java.util.UUID}s, items as {@link net.knightsandkings.knk.core.duel.StakeItem}
 * references. The Paper runtime (plan Phase 4, {@code DuelService}) applies the returned effects.
 * Not thread-safe: main thread only.
 */
package net.knightsandkings.knk.core.duel;
