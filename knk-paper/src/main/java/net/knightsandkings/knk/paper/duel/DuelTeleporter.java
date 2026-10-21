package net.knightsandkings.knk.paper.duel;

import net.knightsandkings.knk.core.teleport.TeleportDenial;
import net.knightsandkings.knk.core.teleport.TeleportOutcome;
import net.knightsandkings.knk.core.teleport.WarmupCancelReason;
import net.knightsandkings.knk.paper.teleport.TeleportPlan;
import net.knightsandkings.knk.paper.teleport.TeleportService;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The duel's moves, all through the teleport engine (arena duels DESIGN D6/D7): the lobby teleport
 * ({@code ARENA}: warmup and combat tag, no cooldown or charge, with the kit vault as its pre-commit step) and the
 * runtime's own moves ({@code ARENA_SYSTEM}: instant, safe spot).
 */
public interface DuelTeleporter {

    CompletableFuture<TeleportOutcome> lobby(Player player, Location to, TeleportPlan.BeforeMove beforeMove);

    CompletableFuture<TeleportOutcome> system(Player player, Location to, String label);

    /** The engine's guards for the lobby teleport, without starting it (lock-in pre-check, DESIGN §6.5). */
    CompletableFuture<Optional<TeleportDenial>> checkLobby(Player player, Location to);

    /** Stop a running lobby warmup (the other player's teleport failed). */
    void cancelWarmup(UUID player);

    boolean isCombatTagged(UUID player);

    /** ENDING clears both duelists' combat tags (DESIGN §8.7). */
    void clearCombatTag(UUID player);

    /** The engine itself. */
    static DuelTeleporter engine(TeleportService engine) {
        Objects.requireNonNull(engine, "engine");
        return new DuelTeleporter() {
            @Override
            public CompletableFuture<TeleportOutcome> lobby(Player player, Location to, TeleportPlan.BeforeMove beforeMove) {
                Location target = to.clone();
                return engine.start(TeleportPlan.arena(player, target::clone, "arena lobby", beforeMove));
            }

            @Override
            public CompletableFuture<TeleportOutcome> system(Player player, Location to, String label) {
                return engine.start(TeleportPlan.arenaSystem(player, to, label));
            }

            @Override
            public CompletableFuture<Optional<TeleportDenial>> checkLobby(Player player, Location to) {
                Location target = to.clone();
                return engine.check(TeleportPlan.arena(player, target::clone, "arena lobby", null));
            }

            @Override
            public void cancelWarmup(UUID player) {
                engine.cancelWarmup(player, WarmupCancelReason.REPLACED);
            }

            @Override
            public boolean isCombatTagged(UUID player) {
                return engine.combatTags().isTagged(player, engine.now());
            }

            @Override
            public void clearCombatTag(UUID player) {
                engine.combatTags().clear(player);
            }
        };
    }
}
