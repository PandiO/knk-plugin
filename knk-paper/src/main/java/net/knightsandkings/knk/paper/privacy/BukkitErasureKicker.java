package net.knightsandkings.knk.paper.privacy;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.knightsandkings.knk.core.privacy.AccountErasureService;
import net.knightsandkings.knk.core.privacy.AccountErasureService.Kick;
import net.knightsandkings.knk.core.privacy.ErasedAccount;
import net.knightsandkings.knk.core.privacy.LocalDataEraser;

/**
 * Kicks the player of an erased account if they are online (PandiO/knk-workspace#184), with the neutral
 * {@code realtime.erasure.kick-message}, on the main thread. An account without a former UUID can't be online here.
 * <p>
 * Right before the kick, in the same main-thread task, it runs the {@link LocalDataEraser.Where#BEFORE_KICK} steps:
 * what the server held for the player goes back to them (the siege vault; developer decision 2026-11-06 on LR1-8). A
 * failing step is logged by the eraser (erasure id only) and the kick still happens.
 */
public final class BukkitErasureKicker implements AccountErasureService.Kicker {

    private final Executor mainThread;
    private final Function<UUID, Player> onlinePlayer;
    private final String message;
    private final LocalDataEraser returnsFirst;

    public BukkitErasureKicker(Executor mainThread, Function<UUID, Player> onlinePlayer, String message) {
        this(mainThread, onlinePlayer, message, null);
    }

    /** @param returnsFirst whose {@code BEFORE_KICK} steps run before the kick; null = kick only */
    public BukkitErasureKicker(Executor mainThread, Function<UUID, Player> onlinePlayer, String message,
                               LocalDataEraser returnsFirst) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.onlinePlayer = Objects.requireNonNull(onlinePlayer, "onlinePlayer");
        this.message = Objects.requireNonNull(message, "message");
        this.returnsFirst = returnsFirst;
    }

    @Override
    public CompletableFuture<Boolean> kick(ErasedAccount account) {
        return returnAndKick(account).thenApply(Kick::kicked);
    }

    @Override
    public CompletableFuture<Kick> returnAndKick(ErasedAccount account) {
        if (account.formerUuid() == null) {
            return CompletableFuture.completedFuture(Kick.of(false));
        }
        return CompletableFuture.supplyAsync(() -> {
            Player player = onlinePlayer.apply(account.formerUuid());
            if (player == null || !player.isOnline()) {
                return Kick.of(false);
            }
            LocalDataEraser.Outcome returned = returnsFirst == null
                ? null : returnsFirst.run(LocalDataEraser.Where.BEFORE_KICK, account);
            player.kick(Component.text(message));
            return returned == null ? Kick.of(true) : new Kick(true, returned.erased(), returned.failed());
        }, mainThread);
    }
}
